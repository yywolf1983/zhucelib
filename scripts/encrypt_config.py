#!/usr/bin/env python3

import sys
import os
import hashlib

MASTER_KEY = bytes([
    0x52, 0x65, 0x67, 0x47,
    0x61, 0x74, 0x65, 0x4C,
    0x69, 0x62, 0x32, 0x30,
    0x32, 0x34, 0x4B, 0x65,
    0x79, 0x79, 0x4E, 0x6F,
    0x6E, 0x65, 0x73, 0x54,
    0x6F, 0x70, 0x41, 0x70,
    0x70, 0x4B, 0x65, 0x79
])

def derive_key():
    return hashlib.sha256(MASTER_KEY).digest()

def aes_encrypt(key, plaintext, iv):
    block_size = 16
    
    def xor_bytes(a, b):
        return bytes(x ^ y for x, y in zip(a, b))
    
    def add_round_key(state, round_key):
        return xor_bytes(state, round_key)
    
    sbox = [
        0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5,
        0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
        0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0,
        0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
        0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC,
        0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
        0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A,
        0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
        0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0,
        0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
        0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B,
        0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
        0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85,
        0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
        0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5,
        0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
        0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17,
        0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
        0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88,
        0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
        0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C,
        0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
        0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9,
        0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
        0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6,
        0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
        0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E,
        0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
        0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94,
        0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
        0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68,
        0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16
    ]
    
    def sub_bytes(state):
        return bytes(sbox[b] for b in state)
    
    def shift_rows(state):
        result = bytearray(16)
        result[0] = state[0]
        result[1] = state[5]
        result[2] = state[10]
        result[3] = state[15]
        result[4] = state[4]
        result[5] = state[9]
        result[6] = state[14]
        result[7] = state[3]
        result[8] = state[8]
        result[9] = state[13]
        result[10] = state[2]
        result[11] = state[7]
        result[12] = state[12]
        result[13] = state[1]
        result[14] = state[6]
        result[15] = state[11]
        return bytes(result)
    
    def mix_columns(state):
        def xtime(b):
            return ((b << 1) ^ 0x1B) & 0xFF if (b & 0x80) else (b << 1)
        
        result = bytearray(16)
        for i in range(4):
            col = state[i*4:(i+1)*4]
            t0 = col[0] ^ col[1] ^ col[2] ^ col[3]
            t1 = col[0]
            t2 = col[1]
            t3 = col[2]
            t4 = col[3]
            t1 ^= xtime(col[0] ^ col[1])
            t2 ^= xtime(col[1] ^ col[2])
            t3 ^= xtime(col[2] ^ col[3])
            t4 ^= xtime(col[3] ^ col[0])
            result[i*4] = t1 ^ t0
            result[i*4+1] = t2 ^ t0
            result[i*4+2] = t3 ^ t0
            result[i*4+3] = t4 ^ t0
        return bytes(result)
    
    def expand_key(key):
        sbox = [
            0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5,
            0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
            0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0,
            0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
            0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC,
            0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
            0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A,
            0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
            0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0,
            0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
            0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B,
            0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
            0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85,
            0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
            0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5,
            0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
            0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17,
            0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
            0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88,
            0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
            0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C,
            0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
            0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9,
            0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
            0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6,
            0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
            0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E,
            0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
            0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94,
            0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
            0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68,
            0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16
        ]
        rcon = [0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36]
        
        expanded = list(key)
        for i in range(len(key), 240):
            temp = expanded[i-4:i]
            if i % len(key) == 0:
                temp = [sbox[b] for b in temp[1:] + temp[:1]]
                temp[0] ^= rcon[i // len(key)]
            elif len(key) > 24 and i % len(key) == 16:
                temp = [sbox[b] for b in temp]
            expanded.extend([expanded[i-len(key) + j] ^ temp[j] for j in range(4)])
        return bytes(expanded)
    
    expanded_key = expand_key(key)
    num_rounds = 14
    
    plaintext = bytearray(plaintext)
    while len(plaintext) % block_size != 0:
        plaintext.append(0)
    
    ciphertext = bytearray()
    for i in range(0, len(plaintext), block_size):
        block = plaintext[i:i+block_size]
        
        block = add_round_key(block, expanded_key[:16])
        
        for round in range(1, num_rounds):
            block = sub_bytes(block)
            block = shift_rows(block)
            block = mix_columns(block)
            block = add_round_key(block, expanded_key[round*16:(round+1)*16])
        
        block = sub_bytes(block)
        block = shift_rows(block)
        block = add_round_key(block, expanded_key[num_rounds*16:(num_rounds+1)*16])
        
        ciphertext.extend(block)
    
    return bytes(ciphertext)

def gcm_mult(h, x):
    if len(x) < 16:
        x = x + b'\x00' * (16 - len(x))
    
    z = bytes(16)
    for i in range(16):
        if (x[i] & 0x80):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x40):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x20):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x10):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x08):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x04):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x02):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
        if (x[i] & 0x01):
            z = bytes(a ^ b for a, b in zip(z, h))
        h = (h[0] << 1 | (h[1] >> 7)) & 0xFF, \
            (h[1] << 1 | (h[2] >> 7)) & 0xFF, \
            (h[2] << 1 | (h[3] >> 7)) & 0xFF, \
            (h[3] << 1 | (h[4] >> 7)) & 0xFF, \
            (h[4] << 1 | (h[5] >> 7)) & 0xFF, \
            (h[5] << 1 | (h[6] >> 7)) & 0xFF, \
            (h[6] << 1 | (h[7] >> 7)) & 0xFF, \
            (h[7] << 1 | (h[8] >> 7)) & 0xFF, \
            (h[8] << 1 | (h[9] >> 7)) & 0xFF, \
            (h[9] << 1 | (h[10] >> 7)) & 0xFF, \
            (h[10] << 1 | (h[11] >> 7)) & 0xFF, \
            (h[12] << 1 | (h[13] >> 7)) & 0xFF, \
            (h[13] << 1 | (h[14] >> 7)) & 0xFF, \
            (h[14] << 1 | (h[15] >> 7)) & 0xFF, \
            (h[15] << 1) ^ (0xE1 if h[15] & 0x80 else 0) & 0xFF
    return bytes(z)

def encrypt(plaintext):
    key = derive_key()
    iv = os.urandom(12)
    
    h = aes_encrypt(key, bytes(16), iv)
    
    num_blocks = (len(plaintext) + 15) // 16
    if num_blocks == 0:
        num_blocks = 1
    
    y0 = iv + b'\x00\x00\x00\x01'
    s = gcm_mult(h, y0)
    
    ciphertext = bytearray()
    for i in range(num_blocks):
        block = plaintext[i*16:(i+1)*16]
        if len(block) < 16:
            block = block + b'\x00' * (16 - len(block))
        
        y = iv + bytes([(i+2) >> 24 & 0xFF, (i+2) >> 16 & 0xFF, (i+2) >> 8 & 0xFF, (i+2) & 0xFF])
        s = bytes(a ^ b for a, b in zip(s, gcm_mult(h, y)))
        
        encrypted_block = aes_encrypt(key, block, iv)
        ciphertext.extend(encrypted_block[:len(plaintext) - i*16] if i == num_blocks - 1 else encrypted_block)
    
    len_bits = len(plaintext) * 8
    len_block = bytes([
        (len_bits >> 56) & 0xFF, (len_bits >> 48) & 0xFF,
        (len_bits >> 40) & 0xFF, (len_bits >> 32) & 0xFF,
        (len_bits >> 24) & 0xFF, (len_bits >> 16) & 0xFF,
        (len_bits >> 8) & 0xFF, len_bits & 0xFF,
        0, 0, 0, 0, 0, 0, 0, 0
    ])
    
    s = bytes(a ^ b for a, b in zip(s, gcm_mult(h, len_block)))
    tag = aes_encrypt(key, s, iv)
    
    return iv + ciphertext + tag

def main():
    if len(sys.argv) != 3:
        print("Usage: python encrypt_config.py <input_json> <output_dat>")
        sys.exit(1)
    
    input_path = sys.argv[1]
    output_path = sys.argv[2]
    
    if not os.path.exists(input_path):
        print(f"Error: Input file not found: {input_path}")
        sys.exit(1)
    
    with open(input_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    encrypted = encrypt(content.encode('utf-8'))
    
    output_dir = os.path.dirname(output_path)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    with open(output_path, 'wb') as f:
        f.write(encrypted)
    
    print(f"Encrypted: {input_path} -> {output_path}")

if __name__ == '__main__':
    main()