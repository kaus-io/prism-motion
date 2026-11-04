package com.zxhhyj.prism.motion

import android.view.Surface

/** Android 侧的 [PrismMotionSurface]：包装 `TextureView` / `SurfaceView` / 外部 Surface 提供的 [Surface]。 */
public class AndroidPrismMotionSurface(public val surface: Surface) : PrismMotionSurface
