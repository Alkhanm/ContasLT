package br.com.contaslt.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Datas como texto {@code AAAA-MM-DD} e meses como {@code AAAA-MM} (porte de frontend/src/domain/dates.ts), com a
 * aritmética do java.time: somar meses mantém o dia, limitado ao fim do mês (31/01 + 1 mês = 28 ou 29/02). Sem fuso:
 * tudo é data civil. Comparações de datas são comparações de texto, como no PWA.
 */
public final class Dates {

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");

    private static volatile Clock clock = Clock.systemDefaultZone();
    /** Data de hoje fixada pelos testes (equivale ao vi.setSystemTime do PWA). */
    private static volatile String fixedToday;

    private Dates() {
    }

    // ---------- Relógio (substituível nos testes) ----------

    public static void setClockForTests(Clock c) {
        clock = c == null ? Clock.systemDefaultZone() : c;
    }

    /** Fixa o "hoje" (AAAA-MM-DD); null volta ao relógio. */
    public static void setTodayForTests(String date) {
        fixedToday = date;
    }

    public static void resetForTests() {
        fixedToday = null;
        clock = Clock.systemDefaultZone();
    }

    // ---------- Datas ----------

    public static String ymd(int y, int m, int d) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d);
    }

    public static int[] parts(String date) {
        return new int[] {
            Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)),
            Integer.parseInt(date.substring(8, 10)),
        };
    }

    public static LocalDate parse(String date) {
        int[] p = parts(date);
        return LocalDate.of(p[0], p[1], p[2]);
    }

    public static String format(LocalDate d) {
        return ymd(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    public static boolean isLeap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    public static int lengthOfMonth(int y, int m) {
        return YearMonth.of(y, m).lengthOfMonth();
    }

    /** Dias desde 1970-01-01 (LocalDate.toEpochDay). */
    public static long toEpochDay(String date) {
        return parse(date).toEpochDay();
    }

    public static String fromEpochDay(long day) {
        return format(LocalDate.ofEpochDay(day));
    }

    public static String plusDays(String date, long n) {
        return format(parse(date).plusDays(n));
    }

    public static String plusWeeks(String date, long n) {
        return plusDays(date, 7 * n);
    }

    public static String plusMonths(String date, long n) {
        return format(parse(date).plusMonths(n));
    }

    public static String plusYears(String date, long n) {
        return plusMonths(date, 12 * n);
    }

    /** Diferença em dias (b − a), como ChronoUnit.DAYS.between(a, b). */
    public static long daysBetween(String a, String b) {
        return ChronoUnit.DAYS.between(parse(a), parse(b));
    }

    public static int dayOfMonth(String date) {
        return Integer.parseInt(date.substring(8, 10));
    }

    /** Data de hoje no fuso do aparelho. */
    public static String today() {
        String f = fixedToday;
        return f != null ? f : format(LocalDate.now(clock));
    }

    public static String minDate(String a, String b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static String maxDate(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    // ---------- Meses ----------

    /** Os 7 primeiros caracteres (como o slice do PWA, tolerante a texto curto). */
    public static String monthOf(String date) {
        return date.length() <= 7 ? date : date.substring(0, 7);
    }

    public static String currentMonth() {
        return monthOf(today());
    }

    public static String plusMonthsYm(String month, long n) {
        return monthOf(plusMonths(month + "-01", n));
    }

    public static String atDay(String month, int day) {
        int y = Integer.parseInt(month.substring(0, 4));
        int m = Integer.parseInt(month.substring(5, 7));
        return ymd(y, m, Math.min(day, lengthOfMonth(y, m)));
    }

    public static String firstDay(String month) {
        return month + "-01";
    }

    public static String endOfMonth(String month) {
        int y = Integer.parseInt(month.substring(0, 4));
        int m = Integer.parseInt(month.substring(5, 7));
        return ymd(y, m, lengthOfMonth(y, m));
    }

    public static int monthLength(String month) {
        return lengthOfMonth(Integer.parseInt(month.substring(0, 4)), Integer.parseInt(month.substring(5, 7)));
    }

    /** Meses de {@code from} a {@code to}, inclusive. */
    public static List<String> monthsBetween(String from, String to) {
        List<String> out = new ArrayList<>();
        for (String m = from; m.compareTo(to) <= 0 && out.size() < 1200; m = plusMonthsYm(m, 1)) {
            out.add(m);
        }
        return out;
    }

    public static boolean isDate(String s) {
        return s != null && DATE.matcher(s).matches();
    }

    public static boolean isMonth(String s) {
        return s != null && MONTH.matcher(s).matches();
    }

    /** Agora em ISO local sem fuso (como LocalDateTime.toString com milissegundos): createdAt dos registros. */
    public static String nowIso() {
        LocalDateTime d = LocalDateTime.now(clock);
        return String.format(Locale.ROOT, "%sT%02d:%02d:%02d.%03d", format(d.toLocalDate()), d.getHour(), d.getMinute(),
            d.getSecond(), d.getNano() / 1_000_000);
    }

    /** Fuso do aparelho (exposto para quem precisar converter instantes). */
    public static ZoneId zone() {
        return clock.getZone();
    }
}
