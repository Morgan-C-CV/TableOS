#include "card_encoder_decoder.h"
#include <algorithm>
#include <iostream>
#include <memory>
#include <set>

CardEncoderDecoder::CardEncoderDecoder() {
    initializeEncodings();
}

std::string CardEncoderDecoder::Encoding::toString() const {
    return std::to_string(digits[0]) + "," +
           std::to_string(digits[1]) + "," +
           std::to_string(digits[2]) + "," +
           std::to_string(digits[3]);
}

CardEncoderDecoder::DecodeResult CardEncoderDecoder::decodeEncoding(const std::array<int, 4>& encoding) const {
    if (!isValidEncoding(encoding)) {
        return DecodeResult();
    }
    
    Encoding enc(encoding);
    std::string key = enc.toString();
    
    // Try A-group first
    auto aIt = aGroupMap_.find(key);
    if (aIt != aGroupMap_.end()) {
        return DecodeResult(aIt->second, GroupType::GROUP_A);
    }
    
    // Try B-group
    auto bIt = bGroupMap_.find(key);
    if (bIt != bGroupMap_.end()) {
        return DecodeResult(bIt->second, GroupType::GROUP_B);
    }
    
    return DecodeResult();
}

CardEncoderDecoder::DecodeResult CardEncoderDecoder::decodeEncoding(int a, int b, int c, int d) const {
    return decodeEncoding({a, b, c, d});
}

int CardEncoderDecoder::decodeAGroup(const std::array<int, 4>& encoding) const {
    if (!isValidEncoding(encoding)) {
        return -1;
    }
    
    Encoding enc(encoding);
    std::string key = enc.toString();
    
    auto it = aGroupMap_.find(key);
    return (it != aGroupMap_.end()) ? it->second : -1;
}

int CardEncoderDecoder::decodeAGroup(int a, int b, int c, int d) const {
    return decodeAGroup({a, b, c, d});
}

int CardEncoderDecoder::decodeBGroup(const std::array<int, 4>& encoding) const {
    if (!isValidEncoding(encoding)) {
        return -1;
    }
    
    Encoding enc(encoding);
    std::string key = enc.toString();
    
    auto it = bGroupMap_.find(key);
    return (it != bGroupMap_.end()) ? it->second : -1;
}

int CardEncoderDecoder::decodeBGroup(int a, int b, int c, int d) const {
    return decodeBGroup({a, b, c, d});
}

std::unique_ptr<CardEncoderDecoder::CardInfo> CardEncoderDecoder::getCardInfo(int cardId) const {
    auto it = cardInfoMap_.find(cardId);
    if (it != cardInfoMap_.end()) {
        return std::make_unique<CardInfo>(it->second);
    }
    return nullptr;
}

int CardEncoderDecoder::getTotalCards() const {
    return static_cast<int>(cardInfoMap_.size());
}

std::string CardEncoderDecoder::getColorName(int colorIndex) {
    switch (colorIndex) {
        case RED: return "Red";
        case YELLOW: return "Yellow";
        case GREEN: return "Green";
        case CYAN: return "Cyan";
        case BLUE: return "Blue";
        case INDIGO: return "Indigo";
        default: return "Unknown";
    }
}

bool CardEncoderDecoder::isPalindrome(const std::array<int, 4>& encoding) {
    // Check if first two digits equal last two digits: (a,b,a,b)
    return (encoding[0] == encoding[2] && encoding[1] == encoding[3]);
}

CardEncoderDecoder::Encoding CardEncoderDecoder::createMirror(const Encoding& encoding) {
    // Mirror rule: (a,b,c,d) -> (c,d,a,b)
    return Encoding(encoding.digits[2], encoding.digits[3], 
                   encoding.digits[0], encoding.digits[1]);
}

bool CardEncoderDecoder::isValidEncoding(const std::array<int, 4>& encoding) {
    for (int digit : encoding) {
        if (digit < 0 || digit >= NUM_COLORS) {
            return false;
        }
    }
    return true;
}

void CardEncoderDecoder::initializeEncodings() {
    std::vector<Encoding> validEncodings = generateValidEncodings();
    
    int cardId = 1;
    for (const auto& encoding : validEncodings) {
        Encoding mirrorEncoding = createMirror(encoding);
        
        // Store A-group and B-group mappings
        std::string aKey = encoding.toString();
        std::string bKey = mirrorEncoding.toString();
        
        aGroupMap_[aKey] = cardId;
        bGroupMap_[bKey] = cardId;
        
        // Store card info
        CardInfo cardInfo;
        cardInfo.cardId = cardId;
        cardInfo.groupA = encoding;
        cardInfo.groupB = mirrorEncoding;
        cardInfo.groupAColors = encodingToColors(encoding);
        cardInfo.groupBColors = encodingToColors(mirrorEncoding);
        
        cardInfoMap_[cardId] = cardInfo;
        
        cardId++;
    }
}

std::vector<CardEncoderDecoder::Encoding> CardEncoderDecoder::generateValidEncodings() const {
    std::vector<Encoding> validEncodings;
    std::set<std::string> processedEncodings;
    
    // Generate all possible 4-digit combinations
    for (int a = 0; a < NUM_COLORS; a++) {
        for (int b = 0; b < NUM_COLORS; b++) {
            for (int c = 0; c < NUM_COLORS; c++) {
                for (int d = 0; d < NUM_COLORS; d++) {
                    std::array<int, 4> encoding = {a, b, c, d};
                    
                    // Skip palindromes
                    if (isPalindrome(encoding)) {
                        continue;
                    }
                    
                    Encoding enc(encoding);
                    std::string key = enc.toString();
                    
                    // Skip if already processed (avoid duplicates)
                    if (processedEncodings.find(key) != processedEncodings.end()) {
                        continue;
                    }
                    
                    // Create mirror and check if it's already processed
                    Encoding mirror = createMirror(enc);
                    std::string mirrorKey = mirror.toString();
                    
                    if (processedEncodings.find(mirrorKey) != processedEncodings.end()) {
                        continue;
                    }
                    
                    // Add both encodings to processed set
                    processedEncodings.insert(key);
                    processedEncodings.insert(mirrorKey);
                    
                    validEncodings.push_back(enc);
                }
            }
        }
    }
    
    return validEncodings;
}

std::vector<std::string> CardEncoderDecoder::encodingToColors(const Encoding& encoding) const {
    std::vector<std::string> colors;
    for (int digit : encoding.digits) {
        colors.push_back(getColorName(digit));
    }
    return colors;
}