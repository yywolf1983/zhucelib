# 注册码计算算法说明

## 整体架构

基于 **RSA-2048 非对称加密 + SHA256withRSA 签名** 的挑战-响应式注册验证系统。

| 组件 | 角色 |
|------|------|
| **registration-lib** (客户端验证库) | 持有公钥，生成安装码、验证激活码 |
| **keygen-app** (注册机) | 持有私钥，解析安装码、生成签名激活码 |

## 数据字段定义

| 字段 | 长度 | 说明 |
|------|------|------|
| `deviceId` | 12 字节 | 设备指纹 |
| `nonce` | 8 字节 | 加密安全随机数 |
| `validDays` | 2 字节 | 有效天数，大端序 uint16，0=永久 |
| `issuedDay` | 4 字节 | 签发日期，大端序 uint32，自 1970-01-01 的天数 |
| `xorKey` | 6 字节 | XOR 隐写密钥 = SHA-256(deviceId \|\| nonce) 前 6 字节 |
| `keystream` | 262 字节 | 全量 XOR 密钥流 = SHA-256 CTR(deviceId \|\| nonce) |
| `signature` | 256 字节 | RSA-2048 SHA256withRSA 签名 |

---

## 完整计算流程

### 第一步：生成设备指纹（Device ID）

```
raw = AndroidID + "|" + Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.BRAND
deviceId = SHA-256(raw) 的前 12 字节
```

### 第二步：生成随机数（Nonce）

```java
byte[] nonce = new byte[8];
new SecureRandom().nextBytes(nonce);
```

每次生成安装码时创建 8 字节加密安全随机数。

### 第三步：生成安装码（客户端）

```
安装码原始数据(20字节) = deviceId[12] || nonce[8]
安装码字符串 = Crockford Base32 编码(原始数据)，按每组4字符用 "-" 分隔
```

示例格式：`ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ01-2345`

### 第四步：解析安装码（注册机端）

```
raw = Crockford Base32 解码(安装码)
deviceId = raw[0..11]
nonce    = raw[12..19]
```

### 第五步：构建签名消息

```
签名消息(26字节) = deviceId[12] || nonce[8] || validDays[2] || issuedDay[4]
```

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 12 | deviceId（设备指纹） |
| 12 | 8 | nonce（随机挑战值） |
| 20 | 2 | validDays（有效天数，大端序） |
| 22 | 4 | issuedDay（签发天数，大端序） |

其中：`issuedDay = System.currentTimeMillis() / 86400000`

### 第六步：RSA 签名（注册机端）

```java
Signature signer = Signature.getInstance("SHA256withRSA");
signer.initSign(privateKey);
signer.update(msg);  // 26 字节签名消息
byte[] sig = signer.sign();  // 256 字节签名结果
```

### 第七步：构建激活码（注册机端）

```
1. 构建明文载荷(262字节) = validDays[2] || issuedDay[4] || signature[256]
2. 生成密钥流(262字节): keystream = SHA-256 CTR(deviceId || nonce)
   - 块0: SHA-256(deviceId || nonce || 0x00000000)[0:32]
   - 块1: SHA-256(deviceId || nonce || 0x00000001)[0:32]
   - ... (共 ceil(262/32) = 9 块)
3. 全量 XOR 置乱: scrambled[i] = payload[i] XOR keystream[i]
4. 激活码字符串 = Crockford Base32 编码(scrambled)，按每组5字符用 "-" 分隔
```

**全量隐写机制**：整个 262 字节载荷（validDays + issuedDay + 签名）全部用设备绑定的密钥流 XOR 置乱。结果不可区分于随机数据，无任何固定字段或结构特征。

**签名消息不参与隐写**：签名覆盖的 26 字节消息（deviceId + nonce + validDays + issuedDay）保持原始明文，仅激活码的存储/传输格式被混淆。

### 第八步：验证激活码（客户端）

```
1. 去除连字符 → Crockford Base32 解码 → 262 字节置乱数据
2. 生成相同密钥流: keystream[262] = SHA-256 CTR(deviceId || nonce)
3. 全量 XOR 解乱: payload[i] = scrambled[i] XOR keystream[i]
4. 解乱后解析: validDays = payload[0:2], issuedDay = payload[2:6], sig = payload[6:262]
5. 重建签名消息: deviceId[12] + nonce[8] + validDays[2] + issuedDay[4]
6. SHA256withRSA 验签: 用内置公钥验证(签名消息, sig)
7. 验签失败 → 激活码无效
8. 验签成功 → 计算到期时间:
   - issuedMs = issuedDay × 86400000
   - expiryMs = (validDays == 0) ? 0 : (issuedDay + validDays) × 86400000
   - 0 = 永久有效
```

---

## 关键算法汇总

| 操作 | 算法 | 用途 |
|------|------|------|
| 设备指纹 | SHA-256（取前 12 字节） | 生成唯一设备 ID |
| 随机数 | SecureRandom | 生成 8 字节 nonce |
| 编解码 | Crockford Base32 | 安装码/激活码人可读编码 |
| 签名 | SHA256withRSA（RSA-2048） | 私钥签名、公钥验签 |
| 隐写 | SHA-256 CTR 模式派生 262 字节密钥流，全量 XOR | 消除激活码全部固定结构，不可区分于随机 |
| 公钥格式 | X.509 | Base64 公钥解析 |
| 私钥格式 | PKCS#8 | PEM 文件私钥解析 |

### Crockford Base32 细节

- **字母表**: `0123456789ABCDEFGHJKMNPQRSTVWXYZ`（排除 I/L/O/U 避免混淆）
- **解码容错**: `O/o → 0`，`I/i/L/l → 1`
- **分组**: 安装码 4 字符/组，激活码 5 字符/组，用 `-` 分隔
- **输入处理**: 自动去除空格和连字符，大小写不敏感

---

## 安全机制

### 设备绑定
`deviceId`（设备 SHA-256 指纹前 12 字节）参与签名消息，激活码与设备强绑定。不同设备生成的 deviceId 不同，验签必然失败。

### 防重放
`nonce`（每次随机的 8 字节）参与签名消息。同一安装码的 nonce 固定，若重新生成安装码则 nonce 变化，旧激活码无法通过新 nonce 下的验签。

### 防篡改
激活码中的 `validDays` 和 `issuedDay` 受 RSA-2048 签名保护，任何篡改都会导致验签失败。

---

## 时间计算

| 字段 | 计算方式 | 说明 |
|------|----------|------|
| `issuedDay` | `System.currentTimeMillis() / 86400000` | 签发时距 1970-01-01 的天数 |
| `issuedMs` | `issuedDay × 86400000` | 签发时间戳（毫秒） |
| `expiryMs` | `validDays == 0 ? 0 : (issuedDay + validDays) × 86400000` | 到期时间戳，0=永久 |
| `isExpired()` | `expiryMs > 0 && now > expiryMs` | 判断是否过期 |

---

## 密钥管理

| 密钥 | 存储位置 | 用途 |
|------|----------|------|
| 公钥 | `registration-lib/src/main/res/raw/reggate_pub_key.txt` | 编译内置到 AAR，客户端验签 |
| 私钥 | 注册机本地 .pem 文件 | 注册机签名生成激活码 |

密钥对由 `generate_keys.sh` 使用 OpenSSL 生成 RSA-2048 密钥对。
