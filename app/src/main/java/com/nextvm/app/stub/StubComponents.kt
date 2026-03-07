@file:Suppress("ClassName")

package com.nextvm.app.stub

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder

/**
 * StubComponents — Pre-declared Android components for virtual app guest processes.
 *
 * These are placeholder classes declared in AndroidManifest.xml.
 * When a virtual app is launched, ActivityThread.mH hook intercepts the
 * EXECUTE_TRANSACTION message and swaps the stub class name for the real
 * guest app Activity/Service/etc. class name.
 *
 * NAMING CONVENTION:
 * - StubActivity.P{slot}.Standard{nn} — standard launch mode
 * - StubActivity.P{slot}.SingleTop{nn} — singleTop launch mode
 * - StubActivity.P{slot}.SingleTask{nn} — singleTask launch mode
 * - StubActivity.P{slot}.SingleInstance{nn} — singleInstance launch mode
 * - StubService.P{slot}.S{nn} — Service stub
 * - StubContentProvider.P{slot}.CP{nn} — ContentProvider stub
 * - StubReceiver.P{slot}.R{nn} — BroadcastReceiver stub
 *
 * Each process slot (:p0 through :p9) gets its own set of stubs.
 * Total: 10 slots × (10 Activities + 5 Services + 3 Providers + 2 Receivers) = 200 stubs
 */

// ========================================================================
// BASE STUB CLASSES
// ========================================================================

/** Base stub Activity — the mH hook swaps the class before onCreate runs */
open class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The ActivityThread.mH hook has already swapped our class name
        // with the real guest Activity class. If we reach here without
        // a swap, something went wrong.
    }
}

/** Base stub Service */
open class StubService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}

/** Base stub ContentProvider */
open class StubContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}

/** Base stub BroadcastReceiver */
open class StubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { }
}

// ========================================================================
// PROCESS SLOT 0 (:p0)
// ========================================================================
object P0 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 1 (:p1)
// ========================================================================
object P1 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 2 (:p2)
// ========================================================================
object P2 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 3 (:p3)
// ========================================================================
object P3 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 4 (:p4)
// ========================================================================
object P4 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 5 (:p5)
// ========================================================================
object P5 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 6 (:p6)
// ========================================================================
object P6 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 7 (:p7)
// ========================================================================
object P7 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 8 (:p8)
// ========================================================================
object P8 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}

// ========================================================================
// PROCESS SLOT 9 (:p9)
// ========================================================================
object P9 {
    class Standard00 : StubActivity()
    class Standard01 : StubActivity()
    class Standard02 : StubActivity()
    class Standard03 : StubActivity()
    class Standard04 : StubActivity()
    class SingleTop00 : StubActivity()
    class SingleTop01 : StubActivity()
    class SingleTask00 : StubActivity()
    class SingleTask01 : StubActivity()
    class SingleInstance00 : StubActivity()
    class S00 : StubService()
    class S01 : StubService()
    class S02 : StubService()
    class S03 : StubService()
    class S04 : StubService()
    class CP00 : StubContentProvider()
    class CP01 : StubContentProvider()
    class CP02 : StubContentProvider()
    class R00 : StubReceiver()
    class R01 : StubReceiver()
}
