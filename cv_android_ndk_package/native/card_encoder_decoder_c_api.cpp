#include "card_encoder_decoder_c_api.h"
#include "card_encoder_decoder.h"
#include <cstring>
#include <memory>
#include <array>
#include <string>

// Version information
static const char* CARD_DECODER_VERSION = "1.0.0";

// C API Implementation

CardDecoderHandle card_decoder_create() {
    try {
        CardEncoderDecoder* decoder = new CardEncoderDecoder();
        return static_cast<CardDecoderHandle>(decoder);
    } catch (...) {
        return nullptr;
    }
}

void card_decoder_destroy(CardDecoderHandle handle) {
    if (handle) {
        CardEncoderDecoder* decoder = static_cast<CardEncoderDecoder*>(handle);
        delete decoder;
    }
}

DecodeResult card_decode_encoding(CardDecoderHandle handle, int a, int b, int c, int d) {
    DecodeResult result = {-1, -1, 0};
    
    if (!handle) {
        return result;
    }
    
    CardEncoderDecoder* decoder = static_cast<CardEncoderDecoder*>(handle);
    auto cppResult = decoder->decodeEncoding(a, b, c, d);
    
    if (cppResult.success) {
        result.card_id = cppResult.cardId;
        result.group_type = (cppResult.groupType == CardEncoderDecoder::GroupType::GROUP_A) ? 0 : 1;
        result.success = 1;
    }
    
    return result;
}

int card_decode_a_group(CardDecoderHandle handle, int a, int b, int c, int d) {
    if (!handle) {
        return -1;
    }
    
    CardEncoderDecoder* decoder = static_cast<CardEncoderDecoder*>(handle);
    return decoder->decodeAGroup(a, b, c, d);
}

int card_decode_b_group(CardDecoderHandle handle, int a, int b, int c, int d) {
    if (!handle) {
        return -1;
    }
    
    CardEncoderDecoder* decoder = static_cast<CardEncoderDecoder*>(handle);
    return decoder->decodeBGroup(a, b, c, d);
}

int card_get_info(CardDecoderHandle handle, int card_id, CardInfo* info) {
    if (!handle || !info) {
        return 0;
    }
    
    CardEncoderDecoder* decoder = static_cast<CardEncoderDecoder*>(handle);
    auto cppInfo = decoder->getCardInfo(card_id);
    
    if (!cppInfo) {
        return 0;
    }
    
    info->card_id = cppInfo->cardId;
    
    // Copy A-group encoding
    for (int i = 0; i < 4; ++i) {
        info->group_a[i] = cppInfo->groupA.digits[i];
        info->group_b[i] = cppInfo->groupB.digits[i];
    }
    
    return 1;
}

int card_get_total_cards(CardDecoderHandle handle) {
    if (!handle) {
        return 0;
    }
    
    CardEncoderDecoder* decoder = static_cast<CardEncoderDecoder*>(handle);
    return decoder->getTotalCards();
}

int card_get_color_name(int color_index, char* buffer, int buffer_size) {
    if (!buffer || buffer_size <= 0) {
        return -1;
    }
    
    std::string colorName = CardEncoderDecoder::getColorName(color_index);
    
    if (colorName.length() + 1 > static_cast<size_t>(buffer_size)) {
        return -1; // Buffer too small
    }
    
    std::strcpy(buffer, colorName.c_str());
    return static_cast<int>(colorName.length());
}

int card_is_valid_encoding(int a, int b, int c, int d) {
    std::array<int, 4> encoding = {a, b, c, d};
    return CardEncoderDecoder::isValidEncoding(encoding) ? 1 : 0;
}

int card_is_palindrome(int a, int b, int c, int d) {
    std::array<int, 4> encoding = {a, b, c, d};
    return CardEncoderDecoder::isPalindrome(encoding) ? 1 : 0;
}

void card_create_mirror(int a, int b, int c, int d, 
                       int* mirror_a, int* mirror_b, int* mirror_c, int* mirror_d) {
    if (!mirror_a || !mirror_b || !mirror_c || !mirror_d) {
        return;
    }
    
    CardEncoderDecoder::Encoding original(a, b, c, d);
    CardEncoderDecoder::Encoding mirror = CardEncoderDecoder::createMirror(original);
    
    *mirror_a = mirror.digits[0];
    *mirror_b = mirror.digits[1];
    *mirror_c = mirror.digits[2];
    *mirror_d = mirror.digits[3];
}

int card_get_version(char* buffer, int buffer_size) {
    if (!buffer || buffer_size <= 0) {
        return -1;
    }
    
    size_t version_len = std::strlen(CARD_DECODER_VERSION);
    
    if (version_len + 1 > static_cast<size_t>(buffer_size)) {
        return -1; // Buffer too small
    }
    
    std::strcpy(buffer, CARD_DECODER_VERSION);
    return static_cast<int>(version_len);
}