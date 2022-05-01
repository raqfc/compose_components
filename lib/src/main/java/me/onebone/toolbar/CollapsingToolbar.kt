/*
 * Copyright (c) 2021 onebone <me@onebone.me>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package me.onebone.toolbar

import androidx.annotation.FloatRange
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
class CollapsingToolbarState(
	initial: Int = Int.MAX_VALUE
): ScrollableState {
	/**
	 * [height] indicates current height of the toolbar.
	 */
	var height: Int by mutableStateOf(initial)
		private set

	/**
	 * [minHeight] indicates the minimum height of the collapsing toolbar. The toolbar
	 * may collapse its height to [minHeight] but not smaller. This size is determined by
	 * the smallest child.
	 */
	var minHeight: Int
		get() = minHeightState
		internal set(value) {
			minHeightState = value

			if(height < value) {
				height = value
			}
		}

	/**
	 * [maxHeight] indicates the maximum height of the collapsing toolbar. The toolbar
	 * may expand its height to [maxHeight] but not larger. This size is determined by
	 * the largest child.
	 */
	var maxHeight: Int
		get() = maxHeightState
		internal set(value) {
			maxHeightState = value

			if(value < height) {
				height = value
			}
		}

	private var maxHeightState by mutableStateOf(Int.MAX_VALUE)
	private var minHeightState by mutableStateOf(0)

	val progress: Float
		@FloatRange(from = 0.0, to = 1.0)
		get() =
			if(minHeight == maxHeight) {
				0f
			}else{
				((height - minHeight).toFloat() / (maxHeight - minHeight)).coerceIn(0f, 1f)
			}

	private val scrollableState = ScrollableState { value ->
		val consume = if(value < 0) {
			max(minHeight.toFloat() - height, value)
		}else{
			min(maxHeight.toFloat() - height, value)
		}

		val current = consume + deferredConsumption
		val currentInt = current.toInt()

		if(current.absoluteValue > 0) {
			height += currentInt
			deferredConsumption = current - currentInt
		}

		consume
	}

	private var deferredConsumption: Float = 0f

	/**
	 * @return consumed scroll value is returned
	 */
	@Deprecated(
		message = "feedScroll() is deprecated, use dispatchRawDelta() instead.",
		replaceWith = ReplaceWith("dispatchRawDelta(value)")
	)
	fun feedScroll(value: Float): Float = dispatchRawDelta(value)

	@ExperimentalToolbarApi
	suspend fun expand(duration: Int = 200) {
		val anim = AnimationState(height.toFloat())

		scroll {
			var prev = anim.value
			anim.animateTo(maxHeight.toFloat(), tween(duration)) {
				scrollBy(value - prev)
				prev = value
			}
		}
	}

	@ExperimentalToolbarApi
	suspend fun collapse(duration: Int = 200) {
		val anim = AnimationState(height.toFloat())

		scroll {
			var prev = anim.value
			anim.animateTo(minHeight.toFloat(), tween(duration)) {
				scrollBy(value - prev)
				prev = value
			}
		}
	}

	/**
	 * @return Remaining velocity after fling
	 */
	suspend fun fling(flingBehavior: FlingBehavior, velocity: Float): Float {
		var left = velocity
		scroll {
			with(flingBehavior) {
				left = performFling(left)
			}
		}

		return left
	}

	override val isScrollInProgress: Boolean
		get() = scrollableState.isScrollInProgress

	override fun dispatchRawDelta(delta: Float): Float = scrollableState.dispatchRawDelta(delta)

	override suspend fun scroll(
		scrollPriority: MutatePriority,
		block: suspend ScrollScope.() -> Unit
	) = scrollableState.scroll(scrollPriority, block)
}

@Composable
fun rememberCollapsingToolbarState(
	initial: Int = Int.MAX_VALUE
): CollapsingToolbarState {
	return remember {
		CollapsingToolbarState(
			initial = initial
		)
	}
}

@Composable
fun CollapsingToolbar(
	modifier: Modifier = Modifier,
	collapsingToolbarState: CollapsingToolbarState,
	content: @Composable CollapsingToolbarScope.() -> Unit
) {
	val measurePolicy = remember(collapsingToolbarState) {
		CollapsingToolbarMeasurePolicy(collapsingToolbarState)
	}

	Layout(
		content = { CollapsingToolbarScopeInstance.content() },
		measurePolicy = measurePolicy,
		modifier = modifier
			.clipToBounds()
	)
}

private class CollapsingToolbarMeasurePolicy(
	private val collapsingToolbarState: CollapsingToolbarState
): MeasurePolicy {
	override fun MeasureScope.measure(
		measurables: List<Measurable>,
		constraints: Constraints
	): MeasureResult {
		val placeables = ArrayList<Placeable>(measurables.size)
		measurables.mapTo(placeables) {
			it.measure(
				constraints.copy(
					minWidth = 0,
					minHeight = 0,
					maxHeight = Constraints.Infinity
				)
			)
		}

		val placeStrategy = ArrayList<Any?>(measurables.size)
		measurables.mapTo(placeStrategy) { it.parentData }

		val minHeight = placeables.minOfOrNull { it.height }
			?.coerceIn(constraints.minHeight, constraints.maxHeight) ?: 0

		val maxHeight = placeables.maxOfOrNull { it.height }
			?.coerceIn(constraints.minHeight, constraints.maxHeight) ?: 0

		val maxWidth = placeables.maxOfOrNull{ it.width }
			?.coerceIn(constraints.minWidth, constraints.maxWidth) ?: 0

		collapsingToolbarState.also {
			it.minHeight = minHeight
			it.maxHeight = maxHeight
		}

		val height = collapsingToolbarState.height
		return layout(maxWidth, height) {
			val progress = collapsingToolbarState.progress

			placeables.forEachIndexed { i, placeable ->
				val strategy = placeStrategy[i]
				if(strategy is CollapsingToolbarData) {
					strategy.progressListener?.onProgressUpdate(progress)
				}

				when(strategy) {
					is CollapsingToolbarRoadData -> {
						val collapsed = strategy.whenCollapsed
						val expanded = strategy.whenExpanded

						val collapsedOffset = collapsed.align(
							size = IntSize(placeable.width, placeable.height),
							space = IntSize(maxWidth, height),
							layoutDirection = layoutDirection
						)

						val expandedOffset = expanded.align(
							size = IntSize(placeable.width, placeable.height),
							space = IntSize(maxWidth, height),
							layoutDirection = layoutDirection
						)

						val offset = collapsedOffset + (expandedOffset - collapsedOffset) * progress

						placeable.place(offset.x, offset.y)
					}
					is CollapsingToolbarParallaxData ->
						placeable.place(
							x = 0,
							y = -((maxHeight - minHeight) * (1 - progress) * strategy.ratio).roundToInt()
						)
					else -> placeable.place(0, 0)
				}
			}
		}
	}
}

interface CollapsingToolbarScope {
	fun Modifier.progress(listener: ProgressListener): Modifier

	fun Modifier.road(whenCollapsed: Alignment, whenExpanded: Alignment): Modifier

	fun Modifier.parallax(ratio: Float = 0.2f): Modifier

	fun Modifier.pin(): Modifier
}

internal object CollapsingToolbarScopeInstance: CollapsingToolbarScope {
	override fun Modifier.progress(listener: ProgressListener): Modifier {
		return this.then(ProgressUpdateListenerModifier(listener))
	}

	override fun Modifier.road(whenCollapsed: Alignment, whenExpanded: Alignment): Modifier {
		return this.then(RoadModifier(whenCollapsed, whenExpanded))
	}

	override fun Modifier.parallax(ratio: Float): Modifier {
		return this.then(ParallaxModifier(ratio))
	}

	override fun Modifier.pin(): Modifier {
		return this.then(PinModifier())
	}
}

internal class RoadModifier(
	private val whenCollapsed: Alignment,
	private val whenExpanded: Alignment
): ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarRoadData(
			this@RoadModifier.whenCollapsed, this@RoadModifier.whenExpanded,
			(parentData as? CollapsingToolbarData)?.progressListener
		)
	}
}

internal class ParallaxModifier(
	private val ratio: Float
): ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarParallaxData(ratio, (parentData as? CollapsingToolbarData)?.progressListener)
	}
}

internal class PinModifier: ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarPinData((parentData as? CollapsingToolbarData)?.progressListener)
	}
}

internal class ProgressUpdateListenerModifier(
	private val listener: ProgressListener
): ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarProgressData(listener)
	}
}

fun interface ProgressListener {
	fun onProgressUpdate(value: Float)
}

internal sealed class CollapsingToolbarData(
	var progressListener: ProgressListener?
)

internal class CollapsingToolbarProgressData(
	progressListener: ProgressListener?
): CollapsingToolbarData(progressListener)

internal class CollapsingToolbarRoadData(
	var whenCollapsed: Alignment,
	var whenExpanded: Alignment,
	progressListener: ProgressListener? = null
): CollapsingToolbarData(progressListener)

internal class CollapsingToolbarPinData(
	progressListener: ProgressListener? = null
): CollapsingToolbarData(progressListener)

internal class CollapsingToolbarParallaxData(
	var ratio: Float,
	progressListener: ProgressListener? = null
): CollapsingToolbarData(progressListener)
