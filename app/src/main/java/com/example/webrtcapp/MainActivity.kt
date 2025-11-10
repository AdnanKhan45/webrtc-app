package com.example.webrtcapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.content.Context
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID
import com.google.android.material.appbar.MaterialToolbar

import com.example.webrtcapp.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.webrtcapp.ui.UiUser
import com.example.webrtcapp.ui.UsersAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var callIdLayout: TextInputLayout
    private lateinit var callIdInput: TextInputEditText
    private lateinit var btnStartCaller: Button
    private lateinit var btnStartCallee: Button
    private lateinit var statusText: TextView

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText

    private lateinit var usersRecycler: RecyclerView
    private lateinit var usersAdapter: UsersAdapter

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db   by lazy { FirebaseFirestore.getInstance() }

    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- views ----
        titleText = findViewById(R.id.titleText)
        callIdLayout = findViewById(R.id.callIdLayout)
        callIdInput = findViewById(R.id.callIdInput)
        btnStartCaller = findViewById(R.id.btnStartCaller)
        btnStartCallee = findViewById(R.id.btnStartCallee)
        statusText = findViewById(R.id.statusText)
        nameLayout = findViewById(R.id.nameLayout)
        nameInput = findViewById(R.id.nameInput)

        // Pre-fill name (set at onboarding)
        val prefs = getSharedPreferences("webrtc_prefs", Context.MODE_PRIVATE)
        nameInput.setText(prefs.getString("display_name", ""))

        // Randomize Call ID affordance
        callIdLayout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        callIdLayout.setEndIconDrawable(R.drawable.ic_dice)
        callIdLayout.setEndIconOnClickListener {
            callIdInput.setText(defaultCallId())
            callIdInput.setSelection(callIdInput.text?.length ?: 0)
        }

        // Clear-text affordance
        nameLayout.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT

        // Pre-fill a callId for convenience
        if (callIdInput.text.isNullOrBlank()) {
            callIdInput.setText(defaultCallId())
        }

        btnStartCaller.setOnClickListener {
            val callId = normalizedCallId()
            startCall(callId, role = "caller", displayName = "Unknown Caller")
        }

        btnStartCallee.setOnClickListener {
            val callId = normalizedCallId()
            startCall(callId, role = "callee", displayName = "Unknown Callee")
        }

        val topBar = findViewById<MaterialToolbar>(R.id.topBar)
        topBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_random -> {
                    callIdInput.setText(defaultCallId())
                    true
                }
                R.id.action_about -> {
                    statusText.text = "WebRTC demo • Material 3 UI"
                    true
                }
                else -> false
            }
        }


        usersRecycler = findViewById(R.id.usersRecycler)
        usersAdapter = UsersAdapter(onCall = { uiUser ->
            // 1) Generate a short callId
            val callId = UUID.randomUUID().toString().substring(0, 8)

            // 2) Launch CallActivity as caller (your existing flow will create the call doc + offer)
            val displayName = nameInput.text?.toString()?.trim().orEmpty()
            // (persist name like before if you keep that behavior)
            startCall(
                callId = callId,
                role = "caller",
                displayName = displayName,
                calleeUid = uiUser.uid,
                calleeName = uiUser.displayName

            )
            // Callee will get an FCM when your Cloud Function sees the offer appear.
        })

        usersRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = usersAdapter
        }

        observeUsersList()
    }

    private fun observeUsersList() {
        val myUid = auth.currentUser?.uid ?: return
        // Show only users with a deviceToken (means they can receive push), exclude self.
        db.collection("users")
            .whereNotEqualTo("uid", myUid)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { d ->
                    val uid = d.getString("uid") ?: return@mapNotNull null
                    val name = (d.getString("displayName") ?: "").trim()
                    val token = d.getString("deviceToken")
                    UiUser(uid = uid, displayName = name, deviceToken = token)
                }
                // If you want to hide users with no token, filter here:
                // ?.filter { !it.deviceToken.isNullOrBlank() }
                usersAdapter.submit(list ?: emptyList())
            }
    }

    private fun normalizedCallId(): String {
        val raw = callIdInput.text?.toString()?.trim().orEmpty()
        return if (raw.isEmpty()) defaultCallId() else raw
    }

    private fun defaultCallId(): String =
        UUID.randomUUID().toString().substring(0, 8)

    private fun startCall(
        callId: String,
        role: String,
        displayName: String,
        calleeUid: String? = null,
        calleeName: String? = null
    ) {
        getSharedPreferences("webrtc_prefs", Context.MODE_PRIVATE)
            .edit().putString("display_name", displayName).apply()

        ioScope.launch {
            try {
                UserRepository().upsertCurrentUser(
                    displayName = displayName.ifBlank { null },
                    deviceToken = null
                )
            } catch (_: Throwable) {}
        }

        statusText.text = if (role == "caller") "Starting…" else "Joining…"

        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_ROLE, role)
            putExtra("displayName", displayName)
            if (calleeUid != null) putExtra(CallActivity.EXTRA_CALLEE_UID, calleeUid)
            if (!calleeName.isNullOrBlank()) putExtra(CallActivity.EXTRA_CALLEE_NAME, calleeName)
        }
        startActivity(intent)
    }
}