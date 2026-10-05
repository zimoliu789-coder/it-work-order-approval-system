package com.enterprise.ticket.common.util;

import java.security.SecureRandom;

/**
 * 随机临时口令生成器
 *
 * <h2>为什么需要它</h2>
 * <p>系统里有三处需要「生成一个没人知道、也不打算让人长期使用」的口令：
 * <ul>
 *   <li>AD 账号本地化时的占位口令（域账号的凭据在域控，本地哈希只是「不可猜的占位」）；</li>
 *   <li>AD → 本地转换时给管理员展示一次的临时口令；</li>
 *   <li>本地 → AD 转换时把原本地口令替换成不可用的占位串。</li>
 * </ul>
 * 三处若各写一份，迟早出现「其中一处生成的串不满足密码策略」的问题
 * （例如恰好不含数字，而 {@code PasswordPolicyService} 要求至少两类字符）。
 * 因此收敛为唯一实现，并保证<b>结果必然满足本地密码策略的字符类型要求</b>。
 *
 * <h2>安全性</h2>
 * <ul>
 *   <li>用 {@link SecureRandom}（不是 {@code Random}）：后者是可预测的线性同余序列，
 *       一旦被推断出种子，同期生成的口令全部可被算出；</li>
 *   <li>字母表剔除了易混淆字符（{@code I l O 0 1}）：这类串要靠人工抄写录入，
 *       混淆会直接导致「管理员抄错、用户登不进」；</li>
 *   <li>长度默认 32 位，远超策略上限要求，且先行放入各字符类各一个以保证类型数量。</li>
 * </ul>
 */
public final class RandomPasswordGenerator {

    /** 默认长度 */
    public static final int DEFAULT_LENGTH = 32;

    /**
     * 字符表：大写 + 小写 + 数字 + 特殊字符，剔除 {@code I O l 0 1 o} 等易混淆字符。
     */
    private static final char[] ALPHABET =
            ("ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnpqrstuvwxyz" + "23456789" + "!@#$%^&*")
                    .toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomPasswordGenerator() {
    }

    /** 生成 {@value #DEFAULT_LENGTH} 位随机口令 */
    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /**
     * 生成指定长度的随机口令（最短 4 位）。
     *
     * <p>先各放一个大写、小写、数字、特殊字符，再用随机字符补足长度 ——
     * 这样「至少包含 N 类字符」的策略对任意长度都必然满足，
     * 而不是依赖「随机出来恰好有数字」的概率。
     */
    public static String generate(int length) {
        int size = Math.max(length, 4);
        StringBuilder sb = new StringBuilder(size);
        sb.append('A').append('a').append('7').append('!');
        while (sb.length() < size) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        // 打散前 4 位的固定位置：否则「口令以 Aa7! 开头」会成为一个可被利用的模式
        return shuffle(sb.toString());
    }

    private static String shuffle(String value) {
        char[] chars = value.toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
