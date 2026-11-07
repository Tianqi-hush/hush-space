// ============================================================
//  VaultConfig.h — EDIT THIS FILE TO CHANGE YOUR SERVER URL
//  Plain text here, compile-time XOR encoded in binary.
//  No plaintext appears in the final .so file.
// ============================================================
#pragma once

// ── EDIT ONLY THESE TWO LINES ─────────────────────────────────
#define VAULT_URL     "https://sdk.akshit.sbs/api/connect/AKSHIT001"
#define VAULT_XOR_KEY  0x4B
// ──────────────────────────────────────────────────────────────

// Compile-time XOR encoder — do NOT edit below this line
#include <stddef.h>
#include <stdint.h>

template<size_t N, uint8_t KEY>
struct _VaultXorStr {
    uint8_t data[N] = {};
    constexpr _VaultXorStr(const char* s) {
        for (size_t i = 0; i < N; i++)
            data[i] = static_cast<uint8_t>(s[i]) ^ KEY;
    }
    static constexpr size_t len = N;
};

// Macro: encodes the string literal at compile time
// Usage: static constexpr auto kUrl = VAULT_ENC_STR(VAULT_URL);
#define VAULT_ENC_STR(s) _VaultXorStr<sizeof(s)-1, VAULT_XOR_KEY>(s)
