package com.shishonomisaki.lifemanager

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.shishonomisaki.lifemanager.data.Classifier
import com.shishonomisaki.lifemanager.data.DataHub
import com.shishonomisaki.lifemanager.data.Repository
import com.shishonomisaki.lifemanager.data.SettingsStore
import com.shishonomisaki.lifemanager.reminder.ReminderScheduler
import com.shishonomisaki.lifemanager.reminder.ReminderSyncWorker
import com.shishonomisaki.lifemanager.telegram.InboxConnectionService
import com.shishonomisaki.lifemanager.telegram.TelegramWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 簡單的手動相依注入：整個 APP 共用一份。 */
class LifeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    }
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: Repository by lazy { Repository(firestore, auth) }
    val scheduler: ReminderScheduler by lazy { ReminderScheduler(this) }
    val classifier: Classifier by lazy { Classifier(this, settings) }
    val hub: DataHub by lazy { DataHub(auth, repository, scheduler, appScope) }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.createChannel(this)
        ReminderSyncWorker.schedulePeriodic(this)
        TelegramWorker.schedulePeriodic(this)
        InboxConnectionService.start(this)
    }
}
