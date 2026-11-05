package br.com.contaslt.notify;

/** Uma notificação de banco guardada na caixa "A confirmar" (só no aparelho, não sincroniza). */
public class InboxItem {

    public static final String PENDING = "PENDING";
    public static final String UNRECOGNIZED = "UNRECOGNIZED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String DISCARDED = "DISCARDED";

    public String id;
    public String packageName;
    public String appLabel;
    public String title;
    public String text;
    /** epoch ms em que a notificação chegou. */
    public long postedAt;
    /** Hash de pacote + texto, para não guardar a mesma notificação duas vezes. */
    public String hash;
    public String status;

    // Leitura (null quando não reconhecida)
    public String type;
    public long amount;
    public String kind;
    public String counterpart;
    public String cardLast4;
    public String date;
    public String description;

    /** Lançamento criado ao confirmar. */
    public String transactionId;
}
