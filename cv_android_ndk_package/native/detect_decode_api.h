#ifndef DETECT_DECODE_API_H
#define DETECT_DECODE_API_H

#ifdef __cplusplus
extern "C" {
#endif

// Simple result struct for a decoded card
typedef struct {
    int card_id;      // decoded card ID, -1 if not found
    int group_type;   // 0=A, 1=B, -1=unknown
    int tl_x;         // bounding rect top-left x
    int tl_y;         // bounding rect top-left y
    int br_x;         // bounding rect bottom-right x
    int br_y;         // bounding rect bottom-right y
} DetectedCard;

/**
 * Detect and decode cards from a BGR8 image buffer.
 * @param bgr Pointer to BGR8 pixel data (width*height*3 bytes)
 * @param width Image width
 * @param height Image height
 * @param out_cards Output array to fill with detected cards
 * @param max_out_cards Max number of cards to write into out_cards
 * @return Number of decoded cards (>=0)
 */
int detect_decode_cards_bgr8(const unsigned char* bgr, int width, int height,
                             DetectedCard* out_cards, int max_out_cards);

/**
 * Detect and decode cards from an NV21 (YUV420) frame.
 * @param nv21 Pointer to NV21 data (Y plane size width*height, VU plane size width*height/2)
 * @param width Image width
 * @param height Image height
 * @param out_cards Output array to fill with detected cards
 * @param max_out_cards Max number of cards to write into out_cards
 * @return Number of decoded cards (>=0)
 */
int detect_decode_cards_nv21(const unsigned char* nv21, int width, int height,
                             DetectedCard* out_cards, int max_out_cards);

#ifdef __cplusplus
}
#endif

#endif // DETECT_DECODE_API_H