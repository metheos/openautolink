package com.openautolink.app.cluster

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.openautolink.app.diagnostics.DiagnosticLog

/**
 * Makes AndroidX's main-display bootstrap activity non-visible and non-interactive
 * before [androidx.car.app.activity.BaseCarAppActivity] creates its host surface.
 * Once the cluster session is usable, its own task is moved behind the existing
 * foreground task without destroying the Activity or its renderer connection.
 */
internal object ClusterBootstrapWindowProtector : Application.ActivityLifecycleCallbacks {

    private const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
    private val taskState = ClusterBootstrapTaskState<Activity>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity.javaClass.name != CAR_APP_ACTIVITY) return

        val generation = activity.intent.getLongExtra(
            CLUSTER_BINDING_GENERATION_EXTRA,
            Long.MIN_VALUE,
        )
        taskState.protect(generation, activity)

        val window = activity.window
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes = window.attributes
        attributes.alpha = 0f
        window.attributes = attributes
        DiagnosticLog.i(
            "cluster",
            "Cluster bootstrap window protected generation=$generation alpha=0 focusable=false touchable=false",
        )
    }

    fun moveTaskToBack(generation: Long) {
        val target = taskState.requestBackground(generation)
        if (target == null) {
            DiagnosticLog.w(
                "cluster",
                "Cluster bootstrap task background outcome=stale-or-missing generation=$generation",
            )
            return
        }
        postMoveTaskToBack(target)
    }

    private fun postMoveTaskToBack(target: ClusterBootstrapTaskState.Target<Activity>) {
        val activity = target.owner
        activity.window.decorView.post {
            if (!taskState.isCurrent(target) || activity.isFinishing || activity.isDestroyed) {
                return@post
            }

            try {
                val moved = activity.moveTaskToBack(true)
                DiagnosticLog.i(
                    "cluster",
                    "Cluster bootstrap task background outcome=${if (moved) "moved" else "rejected"} generation=${target.generation}",
                )
            } catch (e: Exception) {
                DiagnosticLog.w(
                    "cluster",
                    "Cluster bootstrap task background outcome=failed generation=${target.generation} error=${e.message}",
                )
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        taskState.pendingTargetFor(activity)?.let { postMoveTaskToBack(it) }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        taskState.destroy(activity)
    }
}
