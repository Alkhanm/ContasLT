package br.com.contaslt.notify;

import br.com.contaslt.data.schema.Rec;

/**
 * Uma notificação de banco lida no aparelho. As reconhecidas (PENDING/CONFIRMED/DISCARDED) são o registro
 * sincronizado {@code bankNotification} (frontend/src/data/schema.ts BankNotificationRec): a caixa "A confirmar"
 * aparece também no PWA e o que for confirmado ou descartado lá some daqui. As não reconhecidas ficam só no
 * aparelho ({@link InboxStore}).
 */
public class InboxItem extends Rec {

    public static final String PENDING = "PENDING";
    public static final String UNRECOGNIZED = "UNRECOGNIZED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String DISCARDED = "DISCARDED";

    public String packageName;
    public String appLabel;
    public String title;
    /** Todo o texto da notificação (texto, texto expandido, linhas, subtexto...), sem repetições. */
    public String text;
    /** epoch ms em que a notificação chegou. */
    public long postedAt;
    /** Hash de pacote + texto, para não guardar a mesma notificação duas vezes. */
    public String hash;
    /** Chave da notificação no Android: o banco que atualiza a mesma notificação atualiza o item. */
    public String notificationKey;
    public String status;

    // Leitura (null quando não reconhecida)
    public String type;
    public long amount;
    public String kind;
    public String counterpart;
    public String cardLast4;
    public String date;
    public String description;

    /** Conta e cartão padrão do app do banco no aparelho (para sugerir a conta em qualquer aparelho). */
    public String defaultAccountId;
    public String defaultCardId;

    /** Lançamento criado ao confirmar. */
    public String transactionId;
}
