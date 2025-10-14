#pragma once
#include <array>
#include <string>
#include <memory>
#include <vector>
#include <map>

class CardEncoderDecoder {
public:
    static constexpr int NUM_COLORS = 6;

    enum ColorIndex { RED=0, YELLOW=1, GREEN=2, CYAN=3, BLUE=4, INDIGO=5 };
    enum GroupType { GROUP_A=0, GROUP_B=1 };

    struct Encoding {
        std::array<int, 4> digits;
        Encoding() : digits{0,0,0,0} {}
        Encoding(int a, int b, int c, int d) : digits{a,b,c,d} {}
        Encoding(const std::array<int,4>& e) : digits(e) {}
        std::string toString() const;
    };

    struct DecodeResult {
        int cardId;
        GroupType groupType;
        bool success;
        DecodeResult() : cardId(-1), groupType(GROUP_A), success(false) {}
        DecodeResult(int id, GroupType gt) : cardId(id), groupType(gt), success(true) {}
    };

    struct CardInfo {
        int cardId;
        Encoding groupA;
        Encoding groupB;
        std::vector<std::string> groupAColors;
        std::vector<std::string> groupBColors;
        CardInfo() : cardId(-1), groupA(), groupB(), groupAColors(), groupBColors() {}
    };

    CardEncoderDecoder();

    DecodeResult decodeEncoding(const std::array<int,4>& encoding) const;
    DecodeResult decodeEncoding(int a, int b, int c, int d) const;
    int decodeAGroup(const std::array<int,4>& encoding) const;
    int decodeAGroup(int a, int b, int c, int d) const;
    int decodeBGroup(const std::array<int,4>& encoding) const;
    int decodeBGroup(int a, int b, int c, int d) const;

    std::unique_ptr<CardInfo> getCardInfo(int cardId) const;
    int getTotalCards() const;
    static std::string getColorName(int colorIndex);
    static bool isValidEncoding(const std::array<int,4>& encoding);
    static bool isPalindrome(const std::array<int,4>& encoding);
    static Encoding createMirror(const Encoding& encoding);

private:
    void initializeEncodings();
    std::vector<Encoding> generateValidEncodings() const;
    std::vector<std::string> encodingToColors(const Encoding& encoding) const;

    std::map<std::string, int> aGroupMap_;
    std::map<std::string, int> bGroupMap_;
    std::map<int, CardInfo> cardInfoMap_;
};