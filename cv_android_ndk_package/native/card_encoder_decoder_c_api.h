#ifndef CARD_ENCODER_DECODER_C_API_H
#define CARD_ENCODER_DECODER_C_API_H

#ifdef __cplusplus
extern "C" {
#endif

typedef void* CardDecoderHandle;

typedef struct {
    int card_id;
    int group_type; // 0=A,1=B,-1 fail
    int success;    // 1 success, 0 fail
} DecodeResult;

typedef struct {
    int card_id;
    int group_a[4];
    int group_b[4];
} CardInfo;

CardDecoderHandle card_decoder_create();
void card_decoder_destroy(CardDecoderHandle handle);

DecodeResult card_decode_encoding(CardDecoderHandle handle, int a, int b, int c, int d);
int card_decode_a_group(CardDecoderHandle handle, int a, int b, int c, int d);
int card_decode_b_group(CardDecoderHandle handle, int a, int b, int c, int d);
int card_get_info(CardDecoderHandle handle, int card_id, CardInfo* info);
int card_get_total_cards(CardDecoderHandle handle);
int card_get_color_name(int color_index, char* buffer, int buffer_size);
int card_is_valid_encoding(int a, int b, int c, int d);
int card_is_palindrome(int a, int b, int c, int d);

#ifdef __cplusplus
}
#endif

#endif // CARD_ENCODER_DECODER_C_API_H