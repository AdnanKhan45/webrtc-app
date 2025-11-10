package com.example.webrtcapp.signaling

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Firestore-backed signaling for WebRTC.
 *
 * This layer is "dumb transport":
 *  - Writes/reads offer, answer, and ICE candidates
 *  - Exposes snapshot listeners for real-time updates
 *  - Leaves WebRTC engine work to RtcClient
 *
 * Collection layout:
 *   /calls/{callId}
 *     - status: "idle" | "ringing" | "accepted" | "ended"
 *     - createdAt: serverTimestamp
 *     - caller: { uid, displayName? }
 *     - callee: { uid, displayName? }
 *     - offer:  { type, sdp, createdAt }
 *     - answer: { type, sdp, createdAt }
 *   /calls/{callId}/candidates
 *     - { from: "caller"|"callee", sdpMid, sdpMLineIndex, candidate, createdAt }
 */
class CallRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    // ---------- Data models (DTOs) ----------

    data class Participant(
        val uid: String,
        val displayName: String? = null
    )

    data class SdpDTO(
        val type: String = "",
        val sdp: String = "",
        val createdAt: Timestamp? = null
    )

    data class CandidateDTO(
        val from: String = "",
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
        val candidate: String = "",
        val createdAt: Timestamp? = null
    )

    // ---------- Paths ----------

    private fun calls() = db.collection("calls")
    private fun callDoc(callId: String) = calls().document(callId)
    private fun candidates(callId: String) = callDoc(callId).collection("candidates")

    // ---------- Helpers ----------

    private fun currentUid(): String? = auth.currentUser?.uid

    private fun toSdpDTO(sdp: SessionDescription): Map<String, Any?> = mapOf(
        "type" to sdp.type.canonicalForm(),
        "sdp" to sdp.description,
        "createdAt" to FieldValue.serverTimestamp()
    )

    private fun toCandidateDTO(from: String, c: IceCandidate): Map<String, Any?> = mapOf(
        "from" to from,
        "sdpMid" to c.sdpMid,
        "sdpMLineIndex" to c.sdpMLineIndex,
        "candidate" to c.sdp,
        "createdAt" to FieldValue.serverTimestamp()
    )

    private fun fromSdpDTO(map: Map<*, *>?): SessionDescription? {
        if (map == null) return null
        val typeStr = map["type"] as? String ?: return null
        val sdp     = map["sdp"] as? String ?: return null
        val type    = SessionDescription.Type.fromCanonicalForm(typeStr)
        return SessionDescription(type, sdp)
    }

    private fun fromCandidateDTO(map: Map<*, *>): IceCandidate? {
        val sdpMid        = map["sdpMid"] as? String ?: return null
        val sdpMLineIndex = (map["sdpMLineIndex"] as? Number)?.toInt() ?: return null
        val candidate     = map["candidate"] as? String ?: return null
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    // ---------- High-level ops ----------

    /**
     * Caller creates a call document.
     * Optionally set initial status to "ringing" to indicate an outgoing invite.
     */
    fun createCall(
        callId: String,
        caller: Participant,
        initialStatus: String = "ringing"
    ) = callDoc(callId).set(
        mapOf(
            "status" to initialStatus,
            "createdAt" to FieldValue.serverTimestamp(),
            "caller" to mapOf("uid" to caller.uid, "displayName" to caller.displayName),
            // callee is filled by joinCall later
        )
    )

    /** Callee joins (stores callee info); does not write answer yet. */
    fun joinCall(
        callId: String,
        callee: Participant
    ) = callDoc(callId).update(
        mapOf("callee" to mapOf("uid" to callee.uid, "displayName" to callee.displayName))
    )

    /** Caller publishes the SDP offer. */
    fun setOffer(callId: String, offer: SessionDescription) =
        callDoc(callId).update("offer", toSdpDTO(offer))

    /** Callee publishes the SDP answer. */
    fun setAnswer(callId: String, answer: SessionDescription) =
        callDoc(callId).update("answer", toSdpDTO(answer))

    /**
     * Callee listens for the Offer (or caller can also listen for updates).
     * Returns a ListenerRegistration you must hold and remove() when done.
     */
    fun listenOffer(
        callId: String,
        onOffer: (SessionDescription) -> Unit,
        onError: (Exception) -> Unit = {}
    ): ListenerRegistration =
        callDoc(callId).addSnapshotListener { snap, e ->
            if (e != null) { onError(e); return@addSnapshotListener }
            val offer  = fromSdpDTO(snap?.get("offer")  as? Map<*, *>)
            if (offer != null) onOffer(offer)
        }

    /** Caller listens for the Answer. */
    fun listenAnswer(
        callId: String,
        onAnswer: (SessionDescription) -> Unit,
        onError: (Exception) -> Unit = {}
    ): ListenerRegistration =
        callDoc(callId).addSnapshotListener { snap, e ->
            if (e != null) { onError(e); return@addSnapshotListener }
            val answer = fromSdpDTO(snap?.get("answer") as? Map<*, *>)
            if (answer != null) onAnswer(answer)
        }

    /** Either side can update the high-level status. */
    fun updateStatus(callId: String, status: String) =
        callDoc(callId).update("status", status)

    /** End the call (optionally set a reason). */
    fun endCall(callId: String, reason: String? = null) =
        callDoc(callId).update(
            mapOf(
                "status" to "ended",
                "endedReason" to reason
            )
        )

    // ---------- Trickle ICE ----------

    /**
     * Write a local candidate.
     * @param from "caller" or "callee"
     */
    fun addLocalCandidate(
        callId: String,
        from: String,
        candidate: IceCandidate
    ) = candidates(callId).add(toCandidateDTO(from, candidate))

    /**
     * Listen for remote candidates.
     * Example: caller listens for from="callee"; callee listens for from="caller".
     *
     * IMPORTANT: you should hold and remove() this listener on hangup to avoid leaks.
     */
    fun listenRemoteCandidates(
        callId: String,
        from: String,
        onCandidate: (IceCandidate) -> Unit,
        onError: (Exception) -> Unit = {}
    ): ListenerRegistration =
        candidates(callId)
            .whereEqualTo("from", from)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null) { onError(e); return@addSnapshotListener }
                if (snap == null || snap.isEmpty) return@addSnapshotListener
                for (doc in snap.documentChanges) {
                    // Only act on ADDED changes to avoid re-adding old ones
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        fromCandidateDTO(doc.document.data as Map<*, *>)?.let(onCandidate)                    }
                }
            }
}