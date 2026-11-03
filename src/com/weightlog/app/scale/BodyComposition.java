package com.weightlog.app.scale;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 体成分估算：体脂率 / BMI / 基础代谢。
 *
 * 纯 Java，不 import android.*，Mac 上可以直接用 JVM 验证。
 *
 * 体脂公式是「Mi 社区 / 米家 App 体脂估算公式（v1）」：单条 LBM 公式 + 性别/年龄/体重/身高修正，
 * 输出一个点估计。它**不是** scale/metrics.py + scale/指标计算.md 的「指标计算 v2」：
 * v2 用 2003 中国人群 50 kHz 公式 + Deurenberg，并给出区间，两者不可混用。
 *
 * {@link #CALC_VERSION} 写入 measurement.calc_version；体脂公式一旦变化必须 bump 这个版本号，
 * 否则历史记录无法区分算法族、也无法选择性重算。
 * 结果只是近似值，不是米家官方算法，UI 与导出都要标注「估算」。
 */
public final class BodyComposition {

    /** 公式版本：写入 measurement.calc_version，以后调公式可据此重算 */
    public static final int CALC_VERSION = 1;

    private BodyComposition() {
    }

    /** 统一性别取值：返回 "female" 或 "male"。 */
    public static String normalizeSex(String sex) {
        if (sex != null && "female".equalsIgnoreCase(sex.trim())) {
            return "female";
        }
        return "male";
    }

    /**
     * 体脂率估算（%），Mi 社区 / 米家 App 体脂估算公式（v1）。
     * 返回 null 表示输入不足（身高/年龄缺失、阻抗异常等）。
     */
    public static Double bodyFatPct(double weightKg, double impedance, int heightCm, int age, String sex) {
        if (!(weightKg >= 10 && weightKg <= 200 && impedance > 0 && heightCm > 0 && age > 0)) {
            return null;
        }
        double h = (double) heightCm;
        double lbm = (h * 9.058 / 100.0) * (h / 100.0);
        lbm += weightKg * 0.32 + 12.226;
        lbm -= impedance * 0.0068;
        lbm -= age * 0.0542;

        boolean female = "female".equals(normalizeSex(sex));
        double constant;
        if (female) {
            constant = age <= 49 ? 9.25 : 7.25;
        } else {
            constant = 0.8;
        }

        double coefficient = 1.0;
        if (!female && weightKg < 61) {
            coefficient = 0.98;
        } else if (female && weightKg > 60) {
            coefficient = 0.96 * (heightCm > 160 ? 1.03 : 1.0);
        } else if (female && weightKg < 50) {
            coefficient = 1.02 * (heightCm > 160 ? 1.03 : 1.0);
        }

        double fat = (1 - (((lbm - constant) * coefficient) / weightKg)) * 100;
        double clamped = Math.min(Math.max(fat, 5.0), 75.0);
        return Double.valueOf(round1(clamped));
    }

    /** BMI = weight / height(m)^2，保留 1 位小数；输入不足返回 null。 */
    public static Double bmi(double weightKg, int heightCm) {
        if (weightKg <= 0 || heightCm <= 0) {
            return null;
        }
        double heightM = heightCm / 100.0;
        return Double.valueOf(round1(weightKg / (heightM * heightM)));
    }

    /**
     * 基础代谢（Mifflin-St Jeor）：10w + 6.25h − 5a + (男 +5 / 女 −161)，取整 kcal。
     * 输入不足返回 null。
     */
    public static Integer bmrKcal(double weightKg, int heightCm, int age, String sex) {
        if (weightKg <= 0 || heightCm <= 0 || age <= 0) {
            return null;
        }
        boolean female = "female".equals(normalizeSex(sex));
        double bmr = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age + (female ? -161.0 : 5.0);
        return Integer.valueOf((int) Math.round(bmr));
    }

    /**
     * 保留 1 位小数，按「半数取偶」（BigDecimal HALF_EVEN，对 double 的精确二进制值舍入）。
     */
    private static double round1(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }
}
