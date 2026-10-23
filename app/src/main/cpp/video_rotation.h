#ifndef XBW_VIDEO_ROTATION_H
#define XBW_VIDEO_ROTATION_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>

/* libretro 的 1/2/3 分别为逆时针 90/180/270 度，不能当成顺时针。 */
static inline unsigned xbw_effective_rotation(bool enabled, unsigned requested) {
    return enabled && requested <= 3u ? requested : 0u;
}

/* 只在核心原始分辨率上旋转，不把每帧放大到电视分辨率后再处理。
 * src/dst 必须是不同缓冲；dst 至少容纳 width*height*bpp 字节。
 * 兼容带行填充的源帧；颜色格式不变，继续由 blit_frame 转换。 */
static inline bool xbw_copy_rotated_frame(const void *source, unsigned width,
        unsigned height, size_t pitch, unsigned bpp, unsigned rotation, void *dest) {
    if (!source || !dest || !width || !height || rotation > 3u ||
        (bpp != 2u && bpp != 4u)) return false;
    if ((size_t)width > SIZE_MAX / bpp ||
        (size_t)height > SIZE_MAX / ((size_t)width * bpp) ||
        pitch < (size_t)width * bpp || (size_t)height > SIZE_MAX / pitch) return false;
    const uint8_t *src = (const uint8_t *)source;
    uint8_t *dst = (uint8_t *)dest;
    const size_t row_bytes = (size_t)width * bpp;
    if (rotation == 0u) {
        for (unsigned y = 0; y < height; ++y)
            memcpy(dst + (size_t)y * row_bytes, src + (size_t)y * pitch, row_bytes);
        return true;
    }
    const unsigned out_width = (rotation & 1u) ? height : width;
    for (unsigned y = 0; y < height; ++y) {
        for (unsigned x = 0; x < width; ++x) {
            unsigned dx, dy;
            if (rotation == 1u) { dx = y; dy = width - 1u - x; }
            else if (rotation == 2u) { dx = width - 1u - x; dy = height - 1u - y; }
            else { dx = height - 1u - y; dy = x; }
            const uint8_t *s = src + (size_t)y * pitch + (size_t)x * bpp;
            uint8_t *d = dst + ((size_t)dy * out_width + dx) * bpp;
            // 固定长度的 memcpy 可内联为一次读写，同时支持未对齐的帧缓冲。
            if (bpp == 2u) memcpy(d, s, 2u);
            else memcpy(d, s, 4u);
        }
    }
    return true;
}

#endif
