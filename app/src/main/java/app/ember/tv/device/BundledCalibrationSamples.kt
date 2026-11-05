package app.ember.tv.device

import app.ember.tv.R

/** Original encoded test patterns, registered locally; no URLs or imported-file grants needed. */
internal data class BundledCalibrationSample(val id: String, val title: Int, val kind: CalibrationClip, val resource: Int)

internal object BundledCalibrationSamples {
    val all = listOf(
        BundledCalibrationSample("hdr_black_level", R.string.cal_sample_hdr_black_level, CalibrationClip.HDR10, R.raw.v40_hdr_black_level),
        BundledCalibrationSample("hdr_gray_1000", R.string.cal_sample_hdr_gray_1000, CalibrationClip.HDR10, R.raw.v41_hdr_grayscale_ramp_1000nit),
        BundledCalibrationSample("hdr_gray_4000", R.string.cal_sample_hdr_gray_4000, CalibrationClip.HDR10, R.raw.v42_hdr_grayscale_ramp_4000nit),
        BundledCalibrationSample("hdr_gray_10000", R.string.cal_sample_hdr_gray_10000, CalibrationClip.HDR10, R.raw.v43_hdr_grayscale_ramp_10000nit),
        BundledCalibrationSample("hdr_clip_240_1000", R.string.cal_sample_hdr_clip_240_1000, CalibrationClip.HDR10, R.raw.v50_hdr_color_clipping_240_1000nits_maxcll_1000_mdl_1000),
        BundledCalibrationSample("hdr_clip_900_4000", R.string.cal_sample_hdr_clip_900_4000, CalibrationClip.HDR10, R.raw.v51_hdr_color_clipping_900_4000nits_maxcll_4000_mdl_4000),
        BundledCalibrationSample("hdr_clip_700_10000", R.string.cal_sample_hdr_clip_700_10000, CalibrationClip.HDR10, R.raw.v52_hdr_color_clipping_700_10000nits_maxcll_10000_mdl_10000),
        BundledCalibrationSample("hdr_clip_3600_10000", R.string.cal_sample_hdr_clip_3600_10000, CalibrationClip.HDR10, R.raw.v53_hdr_color_clipping_3600_10000nits_maxcll_10000_mdl_10000),
        BundledCalibrationSample("hdr_geometry", R.string.cal_sample_hdr_geometry, CalibrationClip.HDR10, R.raw.v60_hdr_aspect_ratio_sharpness),
        BundledCalibrationSample("dv_black", R.string.cal_sample_dv_black, CalibrationClip.DOLBY_VISION, R.raw.v70_dv_black_pluge),
        BundledCalibrationSample("dv_bt2111", R.string.cal_sample_dv_bt2111, CalibrationClip.DOLBY_VISION, R.raw.v71_dv_bt2111),
        BundledCalibrationSample("dv_ramps", R.string.cal_sample_dv_ramps, CalibrationClip.DOLBY_VISION, R.raw.v72_dv_color_ramps),
    )
    fun forKind(kind: CalibrationClip): List<BundledCalibrationSample> = all.filter { it.kind == kind }
    fun find(id: String?, kind: CalibrationClip?): BundledCalibrationSample? =
        all.firstOrNull { it.id == id && it.kind == kind }
}
