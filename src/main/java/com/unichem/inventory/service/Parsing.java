package com.unichem.inventory.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Robust parsing for SAP / Excel / CSV values (SAP India DD.MM.YYYY, trailing minus, Indian digit grouping). */
public final class Parsing {
    private Parsing() {}
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    public static double num(String v) {
        if (v == null) return 0;
        String s = v.trim().replaceAll("(?i)[₹\\s]|INR|Rs\\.?", "");
        if (s.isEmpty()) return 0;
        boolean neg = false;
        if (s.endsWith("-")) { neg = true; s = s.substring(0, s.length() - 1); }
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length() - 1); }
        if (s.matches("-?\\d{1,3}(\\.\\d{3})+(,\\d+)?")) s = s.replace(".", "").replace(",", ".");      // 1.234,56
        else if (s.matches("-?\\d+,\\d{1,2}") ) s = s.replace(",", ".");                                     // 12,5
        else s = s.replace(",", "");                                                                         // 1,23,456.50
        try { double n = Double.parseDouble(s); return neg ? -Math.abs(n) : n; } catch (NumberFormatException e) { return 0; }
    }

    private static LocalDate mk(int y, int m, int d) { try { return LocalDate.of(y, m, d); } catch (DateTimeException e) { return null; } }

    /** Returns null for blank; throws IllegalArgumentException for unrecognised text. */
    public static LocalDate date(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty() || s.matches("0+") || s.matches("00[./-]00[./-]0000")) return null;
        Matcher m;
        if ((m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2}).*").matcher(s)).matches()) return req(mk(i(m, 1), i(m, 2), i(m, 3)), s);
        if ((m = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$").matcher(s)).matches()) return req(mk(i(m, 3), i(m, 2), i(m, 1)), s);
        if ((m = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2})$").matcher(s)).matches()) return req(mk(2000 + i(m, 3), i(m, 2), i(m, 1)), s);
        if ((m = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$").matcher(s)).matches()) return req(mk(i(m, 1), i(m, 2), i(m, 3)), s);
        if ((m = Pattern.compile("^(\\d{1,2})[ -]([A-Za-z]{3})[A-Za-z]*[ -,]*(\\d{4})$").matcher(s)).matches()) {
            for (int k = 0; k < 12; k++) if (MONTHS[k].equalsIgnoreCase(m.group(2))) return req(mk(i(m, 3), k + 1, i(m, 1)), s);
        }
        if (s.matches("^\\d{5}(\\.0+)?$")) { double serial = Double.parseDouble(s); if (serial > 20000 && serial < 80000) return LocalDate.of(1899, 12, 30).plusDays((long) serial); }
        throw new IllegalArgumentException("Date \"" + s + "\" not recognised");
    }

    private static LocalDate req(LocalDate d, String s) { if (d == null) throw new IllegalArgumentException("Date \"" + s + "\" not recognised"); return d; }
    private static int i(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    public static String norm(String s) { return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", ""); }
}
