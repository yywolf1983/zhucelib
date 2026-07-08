package com.reggate.lib;

public class ContactInfo {
    private String phone;
    private String email;
    private int qrCodeResId;
    private String website;
    private String shopUrl;
    private String customText;

    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public int getQrCodeResId() { return qrCodeResId; }
    public String getWebsite() { return website; }
    public String getShopUrl() { return shopUrl; }
    public String getCustomText() { return customText; }

    public boolean hasContact() {
        return !isEmpty(phone) || !isEmpty(email) || qrCodeResId != 0 || 
               !isEmpty(website) || !isEmpty(shopUrl) || !isEmpty(customText);
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ContactInfo info = new ContactInfo();

        public Builder phone(String phone) {
            info.phone = phone;
            return this;
        }

        public Builder email(String email) {
            info.email = email;
            return this;
        }

        public Builder qrCodeResId(int resId) {
            info.qrCodeResId = resId;
            return this;
        }

        public Builder website(String website) {
            info.website = website;
            return this;
        }

        public Builder shopUrl(String shopUrl) {
            info.shopUrl = shopUrl;
            return this;
        }

        public Builder customText(String text) {
            info.customText = text;
            return this;
        }

        public ContactInfo build() {
            return info;
        }
    }
}