// 标准库
#include <iostream>
#include <vector>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <cmath>

#include "detect_decode_api.h"
#include "dot_card_detect.h"

static std::string colorIdToName(int id) {
    switch (id) {
        case 0: return "Red";
        case 1: return "Yellow";
        case 2: return "Green";
        case 3: return "Cyan";
        case 4: return "Blue";
        case 5: return "Indigo";
        default: return "Unknown";
    }
}

static int classifyPatchColor(const cv::Mat& hsvPatch,
                              const std::map<std::string, DotCardDetect::ColorRange>& ranges) {
#if HAVE_OPENCV
    // Combine red masks (Red + Red2)
    auto itRed = ranges.find("Red");
    auto itRed2 = ranges.find("Red2");
    cv::Mat redMask;
    if (itRed != ranges.end() && itRed2 != ranges.end()) {
        cv::Mat m1, m2; cv::inRange(hsvPatch, itRed->second.lower, itRed->second.upper, m1);
        cv::inRange(hsvPatch, itRed2->second.lower, itRed2->second.upper, m2);
        cv::bitwise_or(m1, m2, redMask);
    } else if (itRed != ranges.end()) {
        cv::inRange(hsvPatch, itRed->second.lower, itRed->second.upper, redMask);
    }

    struct Candidate { int id; int score; };
    std::vector<Candidate> candidates;
    if (!redMask.empty()) candidates.push_back({0, cv::countNonZero(redMask)});

    auto addColor = [&](const char* name, int id) {
        auto it = ranges.find(name);
        if (it == ranges.end()) return;
        cv::Mat mask; cv::inRange(hsvPatch, it->second.lower, it->second.upper, mask);
        candidates.push_back({id, cv::countNonZero(mask)});
    };

    addColor("Yellow", 1);
    addColor("Green", 2);
    addColor("Cyan", 3);
    addColor("Blue", 4);
    addColor("Indigo", 5);

    int bestId = -1; int bestScore = 0;
    for (const auto& c : candidates) {
        if (c.score > bestScore) { bestScore = c.score; bestId = c.id; }
    }
    // Require minimal evidence to avoid noise
    if (bestScore < 5) return -1;
    return bestId;
#else
    (void)ranges; (void)hsvPatch; 
    return -1;
#endif
}

static cv::Rect makeRoiAround(const cv::Point& p, int size, int imgW, int imgH) {
    int half = size / 2;
    int x = std::max(0, p.x - half);
    int y = std::max(0, p.y - half);
    int w = std::min(size, imgW - x);
    int h = std::min(size, imgH - y);
    return cv::Rect(x, y, w, h);
}

static std::vector<cv::Point> fallbackCornersFromBBox(const DetectedCard& c) {
    return {
        cv::Point(c.tl_x, c.tl_y),
        cv::Point(c.br_x, c.tl_y),
        cv::Point(c.br_x, c.br_y),
        cv::Point(c.tl_x, c.br_y)
    };
}

static double iou(const cv::Rect& a, const cv::Rect& b) {
    int x1 = std::max(a.x, b.x);
    int y1 = std::max(a.y, b.y);
    int x2 = std::min(a.x + a.width, b.x + b.width);
    int y2 = std::min(a.y + a.height, b.y + b.height);
    int iw = std::max(0, x2 - x1);
    int ih = std::max(0, y2 - y1);
    int inter = iw * ih;
    int uni = a.width * a.height + b.width * b.height - inter;
    return uni > 0 ? (double)inter / (double)uni : 0.0;
}

static void ensureOutputDir(const std::string& path) {
#if defined(_WIN32)
    _mkdir(path.c_str());
#else
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) {
        mkdir(path.c_str(), 0755);
    }
#endif
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "Usage: detect_decode_cli <image_path> [--show|--show_regions] [--print_colors]" << std::endl;
        return 1;
    }

    std::string imagePath = argv[1];
    bool showWindows = false;
    bool printColors = false;
    for (int i = 2; i < argc; ++i) {
        std::string opt = argv[i];
        if (opt == "--show" || opt == "--show_regions") showWindows = true;
        if (opt == "--print_colors") printColors = true;
    }
    ensureOutputDir("output");

    // 加载图像与基础检测（在缺少 OpenCV 头文件时进行保护）
    cv::Mat img; 
#if HAVE_OPENCV
    img = cv::imread(imagePath, 1);
    if (img.empty()) {
        std::cerr << "Failed to load image: " << imagePath << std::endl;
        return 2;
    }
    std::cout << "Loaded image: " << imagePath << " (" << img.cols << "x" << img.rows << ")" << std::endl;
#else
    std::cerr << "OpenCV not available for CLI image IO. Install OpenCV (imgcodecs/highgui)." << std::endl;
    return 3;
#endif

    std::vector<DetectedCard> cards(64);
    int n = 0;
#if HAVE_OPENCV
    n = detect_decode_cards_bgr8(img.data, img.cols, img.rows, cards.data(), (int)cards.size());
#endif
    std::cout << "Detected cards: " << n << std::endl;

    // Prepare HSV and default color ranges for corner classification
    cv::Mat hsv; 
#if HAVE_OPENCV
    cv::cvtColor(img, hsv, cv::COLOR_BGR2HSV);
#endif
    auto colorRanges = DotCardDetect::getDefaultColorRanges();
    // Also detect detailed cards for corner positions when available
#if HAVE_OPENCV
    auto detRes = DotCardDetect::detectDotCards(img, showWindows);
#else
    DotCardDetect::DetectionResult detRes;
#endif
    const auto& dcards = detRes.cards;
    const auto& rects = detRes.rectangles;

    for (int i = 0; i < n; ++i) {
        const auto& c = cards[i];
        std::cout << "#" << i
                  << " id: " << c.card_id
                  << " group: " << c.group_type
                  << " bbox: [" << c.tl_x << "," << c.tl_y << "," << c.br_x << "," << c.br_y << "]";

        // Find matching detailed card to get precise corners
        cv::Rect bbox(c.tl_x, c.tl_y, c.br_x - c.tl_x, c.br_y - c.tl_y);
        int matchIdx = -1; double bestIou = 0.0;
        for (int k = 0; k < (int)dcards.size(); ++k) {
            const auto& dc = dcards[k];
            double io = iou(bbox, dc.boundingRect);
            if (io > bestIou) { bestIou = io; matchIdx = k; }
        }

        std::vector<cv::Point> corners;
        if (matchIdx >= 0 && bestIou > 0.1 && !dcards[matchIdx].corners.empty()) {
            corners = dcards[matchIdx].corners; // 顺序：左上、右上、右下、左下
        } else {
            corners = fallbackCornersFromBBox(c);
        }

        // Classify color for each corner and print numeric tuple
        std::vector<int> cornerIds(4, -1);
        for (int ci = 0; ci < (int)corners.size() && ci < 4; ++ci) {
            cv::Rect roi = makeRoiAround(corners[ci], 16, img.cols, img.rows);
#if HAVE_OPENCV
            cv::Mat patch = hsv(roi);
            cornerIds[ci] = classifyPatchColor(patch, colorRanges);
#else
            cornerIds[ci] = -1;
#endif
        }
        std::cout << " colors: (" 
                  << cornerIds[0] << "," << cornerIds[1] << "," 
                  << cornerIds[2] << "," << cornerIds[3] << ")" 
                  << std::endl;

        if (showWindows && c.card_id >= 0) {
#if HAVE_OPENCV
            cv::rectangle(img, cv::Rect(c.tl_x, c.tl_y, c.br_x - c.tl_x, c.br_y - c.tl_y), 0x00FF00, 2);
            // draw corner points
            for (int ci = 0; ci < (int)corners.size() && ci < 4; ++ci) {
                cv::Rect r = makeRoiAround(corners[ci], 8, img.cols, img.rows);
                cv::rectangle(img, r, 0x0000FF, 1);
            }
#endif
        }
    }

    // 打印区域颜色与JSON（固定顺序 U,R,D,L），以及简化4元组
#if HAVE_OPENCV
    if (printColors) {
        const auto& rc = detRes.regionColors;
        if (!rc.empty()) {
            std::cout << "Region colors:" << std::endl;
            std::vector<std::string> order = {"U","R","D","L"};
            for (const auto& key : order) {
                auto it = rc.find(key);
                if (it == rc.end()) continue;
                std::cout << "  Region " << key << ": "
                          << colorIdToName(it->second.first) << "(" << it->second.first << "), "
                          << colorIdToName(it->second.second) << "(" << it->second.second << ")" << std::endl;
            }
            std::string jsonStr = "{";
            bool first = true;
            for (const auto& key : order) {
                auto it = rc.find(key);
                if (it == rc.end()) continue;
                if (!first) jsonStr += ", ";
                jsonStr += "\"" + key + "\":(" + std::to_string(it->second.first) + "," + std::to_string(it->second.second) + ")";
                first = false;
            }
            jsonStr += "}";
            std::cout << "JSON: " << jsonStr << std::endl;
            // 简化4元组：取每个方向的近色ID，若缺失为-1
            int u = rc.count("U") ? rc.at("U").first : -1;
            int r = rc.count("R") ? rc.at("R").first : -1;
            int d = rc.count("D") ? rc.at("D").first : -1;
            int l = rc.count("L") ? rc.at("L").first : -1;
            std::cout << "Simplified JSON: (" << u << "," << r << "," << d << "," << l << ")" << std::endl;

            // 补充输出卡片ID，便于对齐脚本侧输出习惯
            if (!cards.empty()) {
                std::cout << "IDs: ";
                for (int i = 0; i < n; ++i) {
                    if (i) std::cout << ", ";
                    std::cout << cards[i].card_id;
                }
                std::cout << std::endl;
            } else {
                std::cout << "IDs: (none)" << std::endl;
            }
        }
    }
#endif

    // 输出每个 rectangle 的四角颜色编码（按 TL,TR,BR,BL）
#if HAVE_OPENCV
    if (!rects.empty()) {
        std::cout << "Rectangles corner colors:" << std::endl;
        // 选择与卡片 IoU 最大的 rectangle（每张卡片最多一个）；若卡片为空则选面积最大者
        std::vector<size_t> selectedRectIdx;
        std::vector<bool> used(rects.size(), false);
        for (int i = 0; i < n; ++i) {
            const auto& c = cards[i];
            cv::Rect cbbox(c.tl_x, c.tl_y, c.br_x - c.tl_x, c.br_y - c.tl_y);
            cv::Point2f ccenter((c.tl_x + c.br_x) * 0.5f, (c.tl_y + c.br_y) * 0.5f);
            double cardArea = (double)cbbox.width * (double)cbbox.height;

            // 首选 IoU 最大且面积合理的矩形
            double bestIou = 0.0; size_t bestIdx = (size_t)-1; double bestArea = 0.0;
            for (size_t ri = 0; ri < rects.size(); ++ri) {
                if (used[ri]) continue;
                cv::Rect rbox = cv::boundingRect(rects[ri]);
                double io = iou(rbox, cbbox);
                double area = (double)rbox.width * (double)rbox.height;
                double areaRatio = (cardArea > 0) ? (area / cardArea) : 0.0;
                // 过滤过小或过大的矩形：面积比例需在 [0.5, 1.5] 内，确保选择大蓝框
                if (areaRatio < 0.50 || areaRatio > 1.50) continue;
                if (io > bestIou || (io == bestIou && area > bestArea)) {
                    bestIou = io;
                    bestIdx = ri;
                    bestArea = area;
                }
            }

            // 若 IoU 不足，则退化为中心距离最近的较大矩形
            if (bestIdx == (size_t)-1 || bestIou < 0.20) {
                double bestDist = 1e18; size_t bestIdx2 = (size_t)-1; double bestArea2 = 0.0;
                for (size_t ri = 0; ri < rects.size(); ++ri) {
                    if (used[ri]) continue;
                    auto rr = cv::minAreaRect(rects[ri]);
                    cv::Point2f rcenter = rr.center;
                    double dx = rcenter.x - ccenter.x;
                    double dy = rcenter.y - ccenter.y;
                    double dist = dx*dx + dy*dy;
                    cv::Rect rbox = cv::boundingRect(rects[ri]);
                    double area = (double)rbox.width * (double)rbox.height;
                    double areaRatio = (cardArea > 0) ? (area / cardArea) : 0.0;
                    if (areaRatio < 0.50 || areaRatio > 1.50) continue;
                    if (dist < bestDist || (dist == bestDist && area > bestArea2)) {
                        bestDist = dist;
                        bestIdx2 = ri;
                        bestArea2 = area;
                    }
                }
                bestIdx = bestIdx2;
            }

            if (bestIdx != (size_t)-1) {
                selectedRectIdx.push_back(bestIdx);
                used[bestIdx] = true;
            }
        }
        if (selectedRectIdx.empty()) {
            double bestArea = 0.0; size_t bestIdx = (size_t)-1;
            for (size_t ri = 0; ri < rects.size(); ++ri) {
                double area = cv::contourArea(rects[ri]);
                if (area > bestArea) { bestArea = area; bestIdx = ri; }
            }
            if (bestIdx != (size_t)-1) selectedRectIdx.push_back(bestIdx);
        }
        // 如果检测到多张卡片但只选出一个矩形，则补充选择剩余面积最大的矩形，直到与卡片数量对齐
        if ((int)selectedRectIdx.size() < n) {
            std::vector<std::pair<double,size_t>> areas;
            for (size_t ri = 0; ri < rects.size(); ++ri) {
                if (used[ri]) continue;
                double area = cv::contourArea(rects[ri]);
                areas.push_back({area, ri});
            }
            std::sort(areas.begin(), areas.end(), [](auto& a, auto& b){ return a.first > b.first; });
            for (auto& pr : areas) {
                if ((int)selectedRectIdx.size() >= n) break;
                selectedRectIdx.push_back(pr.second);
                used[pr.second] = true;
            }
        }

        for (size_t k = 0; k < selectedRectIdx.size(); ++k) {
            size_t ri = selectedRectIdx[k];
            auto rr = cv::minAreaRect(rects[ri]);
            cv::Point2f pts[4]; rr.points(pts);
            std::vector<cv::Point2f> cornersF(pts, pts+4);
            cornersF = DotCardDetect::sortRectangleCorners(cornersF);
            std::vector<int> codes(4, -1);
            for (int ci = 0; ci < 4; ++ci) {
                cv::Point p((int)cornersF[ci].x, (int)cornersF[ci].y);
                cv::Rect roi = makeRoiAround(p, 16, img.cols, img.rows);
                cv::Mat patch = hsv(roi);
                codes[ci] = classifyPatchColor(patch, colorRanges);
            }
            std::cout << "Rect" << (k+1) << ":" << std::endl;
            std::cout << "  Corner1: " << codes[0] << std::endl;
            std::cout << "  Corner2: " << codes[1] << std::endl;
            std::cout << "  Corner3: " << codes[2] << std::endl;
            std::cout << "  Corner4: " << codes[3] << std::endl;
        }

        // 直接使用配对后的卡片四角（dcards）来输出 JSON，并计算方向角
        std::cout << "Rectangles JSON:" << std::endl;
        std::cout << "{";
        for (size_t k = 0; k < dcards.size(); ++k) {
            const auto& dc = dcards[k];
            
            cv::Point tl, tr, brp, bl, center;
            double angle = 0.0;
            int bestCardId = -1;
            
            if (dc.corners.size() == 4) {
                // 处理四角卡片
                std::vector<cv::Point2f> cf;
                for (const auto& p : dc.corners) cf.emplace_back((float)p.x, (float)p.y);
                // 使用最小外接矩形来稳健地提取四角
                auto rr_card = cv::minAreaRect(cf);
                cv::Point2f ptsCard[4]; rr_card.points(ptsCard);
                std::vector<cv::Point2f> cornersF(ptsCard, ptsCard+4);
                cornersF = DotCardDetect::sortRectangleCorners(cornersF); // TL, TR, BR, BL
                tl = cv::Point((int)cornersF[0].x, (int)cornersF[0].y);
                tr = cv::Point((int)cornersF[1].x, (int)cornersF[1].y);
                brp = cv::Point((int)cornersF[2].x, (int)cornersF[2].y);
                bl = cv::Point((int)cornersF[3].x, (int)cornersF[3].y);
                center = cv::Point((int)rr_card.center.x, (int)rr_card.center.y);

                // 明确使用"左边"边（BL -> TL），并从下到上计算偏移角度
                cv::Point bottom = (bl.y > tl.y) ? bl : tl;
                cv::Point top    = (bl.y > tl.y) ? tl : bl;
                cv::Point pBottom = bottom;
                cv::Point pTop = top;
                double dxv = (double)pTop.x - (double)pBottom.x;
                double dyv = (double)pTop.y - (double)pBottom.y;
                double dyv_up = -dyv;
                double theta_v = std::atan2(dyv_up, dxv);
                angle = (theta_v - M_PI/2.0) * 180.0 / M_PI;
                if (angle > 180.0) angle -= 360.0;
                if (angle <= -180.0) angle += 360.0;

                // 为了输出卡片ID：使用 IoU 将 dc 与粗卡片列表关联
                double bestIou = 0.0;
                for (int i = 0; i < n; ++i) {
                    const auto& c = cards[i];
                    cv::Rect cbbox(c.tl_x, c.tl_y, c.br_x - c.tl_x, c.br_y - c.tl_y);
                    double io = iou(dc.boundingRect, cbbox);
                    if (io > bestIou) { bestIou = io; bestCardId = c.card_id; }
                }
            } else if (dc.corners.size() == 1) {
                // 处理单角点卡片：使用角点ID，角点中心作为rect中心，角点四角作为rect四角
                if (!dc.cornerIndices.empty()) {
                    bestCardId = dc.cornerIndices[0]; // 使用角点ID
                }
                
                // 使用边界矩形的四角作为rect四角
                cv::Rect bbox = dc.boundingRect;
                tl = cv::Point(bbox.x, bbox.y);
                tr = cv::Point(bbox.x + bbox.width, bbox.y);
                brp = cv::Point(bbox.x + bbox.width, bbox.y + bbox.height);
                bl = cv::Point(bbox.x, bbox.y + bbox.height);
                center = cv::Point(bbox.x + bbox.width/2, bbox.y + bbox.height/2);
                angle = 0.0; // 单角点没有方向角
            } else {
                continue; // 跳过其他情况
            }

            if (k) std::cout << ", ";
            std::cout << "\"Rect" << (k+1) << "\": {"
                      << "\"id\": " << bestCardId << ", "
                      << "\"posi\": {"
                      << "\"Corner1\": [" << tl.x << ", " << tl.y << "], "
                      << "\"Corner2\": [" << tr.x << ", " << tr.y << "], "
                      << "\"Corner3\": [" << brp.x << ", " << brp.y << "], "
                      << "\"Corner4\": [" << bl.x << ", " << bl.y << "], "
                      << "\"center\": [" << center.x << ", " << center.y << "]}, "
                      << "\"angle\": " << angle << ", "
                      << "\"direction\": " << angle
                      << "}";
        }
        std::cout << "}" << std::endl;
    }
#endif

    if (showWindows) {
#if HAVE_OPENCV
        cv::imshow("detections", img);
        cv::waitKey(0);
#endif
    }
    return 0;
}