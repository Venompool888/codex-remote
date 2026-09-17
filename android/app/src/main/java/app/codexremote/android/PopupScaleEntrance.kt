package app.codexremote.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Scales the catalog toward its final bounds, keeping the trigger-side corner fixed. */
internal class PopupScaleEntrance(private val fromEnd: Boolean, private val fromBottom: Boolean) : Visibility() {
    init {
        duration = 120L
        interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
    }

    override fun onAppear(sceneRoot: ViewGroup, view: View, startValues: TransitionValues?, endValues: TransitionValues?): Animator {
        view.pivotX = if (fromEnd) view.width.toFloat() else 0f
        view.pivotY = if (fromBottom) view.height.toFloat() else 0f
        return AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, 0.8f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.8f, 1f))
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
                override fun onAnimationCancel(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
        }
    }
}
