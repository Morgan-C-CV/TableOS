#include "detect_decode_api.h"
#include "dot_card_detect.h"
#include "card_encoder_decoder_c_api.h"

#include <vector>
#include <array>
#include <map>
#include <tuple>

static int decode_card_from_corner(
    CardDecoderHandle decoderHandle,
    const cv::Mat& img,
    const std::vector<cv::Point>& approxCorner,
    const cv::Mat& hsv,
    const std::map<std::string, DotCardDetect::ColorRange>& colorRanges,
    const std::map<std::string, cv::Mat>& precomputedColorMasks,
    int& out_card_id,
    int& out_group_type
) {
    cv::Mat img_copy = img.clone();
    auto optRes = DotCardDetect::checkExtendedRegionsForColorsOptimized(
        img_copy,
        approxCorner, hsv, colorRanges, precomputedColorMasks);

    auto regionColors = std::get<2>(optRes);
    // Collect up to two directions with colors
    std::vector<std::string> keys;
    for (const auto& kv : regionColors) {
        if (kv.second.first >= 0 || kv.second.second >= 0) keys.push_back(kv.first);
    }
    if (keys.size() < 2) return 0;

    auto pairForKey = [&](const std::string& key) -> std::pair<int,int> {
        auto it = regionColors.find(key);
        if (it != regionColors.end()) return it->second;
        return {-1, -1};
    };

    auto P0 = pairForKey(keys[0]);
    auto P1 = pairForKey(keys[1]);

    std::vector<std::array<int,4>> candidates;
    candidates.push_back({P0.first, P0.second, P1.first, P1.second});
    candidates.push_back({P1.first, P1.second, P0.first, P0.second});
    candidates.push_back({P0.second, P0.first, P1.second, P1.first});
    candidates.push_back({P1.second, P1.first, P0.second, P0.first});

    for (const auto& enc : candidates) {
        int a = enc[0], b = enc[1], c = enc[2], d = enc[3];
        if (a < 0 || b < 0 || c < 0 || d < 0) continue;
        if (!card_is_valid_encoding(a, b, c, d)) continue;
        DecodeResult dr = card_decode_encoding(decoderHandle, a, b, c, d);
        if (dr.success == 1 && dr.card_id >= 0) {
            out_card_id = dr.card_id;
            out_group_type = dr.group_type;
            return 1;
        }
    }
    return 0;
}

static int detect_decode_cards_impl(const cv::Mat& bgr, DetectedCard* out_cards, int max_out_cards) {
    if (!out_cards || max_out_cards <= 0) return 0;

    // Prepare HSV and color masks
    cv::Mat hsv; cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);
    auto colorRanges = DotCardDetect::getDefaultColorRanges();
    std::map<std::string, cv::Mat> precomputedColorMasks;
    for (const auto& colorPair : colorRanges) {
        const std::string& colorName = colorPair.first;
        const DotCardDetect::ColorRange& colorRange = colorPair.second;
        cv::Mat colorMask;
        if (colorName == "Red") {
            cv::Mat mask1, mask2;
            cv::inRange(hsv, colorRange.lower, colorRange.upper, mask1);
            auto red2It = colorRanges.find("Red2");
            if (red2It != colorRanges.end()) {
                cv::inRange(hsv, red2It->second.lower, red2It->second.upper, mask2);
                cv::bitwise_or(mask1, mask2, colorMask);
            } else {
                colorMask = mask1;
            }
        } else if (colorName == "Red2") {
            continue;
        } else {
            cv::inRange(hsv, colorRange.lower, colorRange.upper, colorMask);
        }
        precomputedColorMasks[colorName] = colorMask;
    }

    // Detect rectangles and pair into cards
    auto det = DotCardDetect::detectDotCards(bgr, false);
    if (!det.success) return 0;

    // Create decoder handle
    CardDecoderHandle handle = card_decoder_create();
    if (!handle) return 0;

    int written = 0;
    for (size_t ci = 0; ci < det.cards.size() && written < max_out_cards; ++ci) {
        const auto& card = det.cards[ci];
        int decodedId = -1; int decodedGroup = -1;
        // Try all corners
        for (size_t k = 0; k < card.cornerIndices.size() && decodedId < 0; ++k) {
            int cornerIdx = card.cornerIndices[k];
            if (cornerIdx < 0 || cornerIdx >= (int)det.rectangles.size()) continue;
            const auto& approxCorner = det.rectangles[cornerIdx];
            int ok = decode_card_from_corner(handle, bgr, approxCorner, hsv, colorRanges, precomputedColorMasks, decodedId, decodedGroup);
            if (ok) break;
        }

        DetectedCard out{};
        out.card_id = decodedId;
        out.group_type = (decodedId >= 0 ? decodedGroup : -1);
        out.tl_x = card.boundingRect.x;
        out.tl_y = card.boundingRect.y;
        out.br_x = card.boundingRect.x + card.boundingRect.width;
        out.br_y = card.boundingRect.y + card.boundingRect.height;

        out_cards[written++] = out;
    }

    card_decoder_destroy(handle);
    return written;
}

int detect_decode_cards_bgr8(const unsigned char* bgr, int width, int height,
                             DetectedCard* out_cards, int max_out_cards) {
    if (!bgr || width <= 0 || height <= 0) return 0;
    cv::Mat mat(height, width, CV_8UC3, (void*)bgr);
    return detect_decode_cards_impl(mat, out_cards, max_out_cards);
}

int detect_decode_cards_nv21(const unsigned char* nv21, int width, int height,
                             DetectedCard* out_cards, int max_out_cards) {
    if (!nv21 || width <= 0 || height <= 0) return 0;
    cv::Mat yuv(height + height/2, width, CV_8UC1, (void*)nv21);
    cv::Mat bgr;
    cv::cvtColor(yuv, bgr, cv::COLOR_YUV2BGR_NV21);
    return detect_decode_cards_impl(bgr, out_cards, max_out_cards);
}