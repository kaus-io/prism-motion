package com.zxhhyj.prism.motion

import android.view.Surface

/** Android 侧的 [AnimationSurface]：包装 `TextureView` / `SurfaceView` / 外部 Surface 提供的 [Surface]。 */
public class AndroidAnimationSurface(public val surface: Surface) : AnimationSurface
