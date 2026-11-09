package br.com.contaslt.ui.form;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.navigation.Navigation;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.data.ApiException;
import br.com.contaslt.databinding.SheetTransactionFormBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.ReceiptScan;
import br.com.contaslt.dto.ScanItem;
import br.com.contaslt.dto.Transaction;
import br.com.contaslt.dto.TransactionFilter;
import br.com.contaslt.dto.TransactionInput;
import br.com.contaslt.dto.TransactionItem;
import br.com.contaslt.dto.TransactionItemInput;
import br.com.contaslt.dto.TransactionPage;
import br.com.contaslt.dto.Transfer;
import br.com.contaslt.dto.TransferInput;
import br.com.contaslt.dto.User;
import br.com.contaslt.services.ReceiptsService;
import br.com.contaslt.services.TransactionsService;
import br.com.contaslt.services.TransfersService;
import br.com.contaslt.ui.AppState;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.TxActions;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.recurring.RecurringFormSheet;
import br.com.contaslt.ui.views.MoneyText;
import br.com.contaslt.ui.views.SegmentedControl;
import br.com.contaslt.ui.views.SelectFieldView;

/**
 * Formulário de lançamento (TransactionFormSheet.vue): nova despesa, receita ou transferência, edição, pagamento de
 * fatura de cartão, leitura de comprovante (com a fila do leitor no computador), itens, escopo de ocorrência
 * recorrente, exclusão e "Tornar recorrente".
 */
public class TransactionFormSheet extends BottomSheetDialogFragment {

    private static final String TAG = "transaction-form";
    private static final long POLL_MS = 3000;
    private static final long POLL_DEADLINE_MS = 180_000;
    private static final int MAX_SIDE = 1600;

    public static void show(FragmentManager fm, AppState.FormRequest request) {
        if (fm.isStateSaved()) {
            return;
        }
        if (fm.findFragmentByTag(TAG) instanceof TransactionFormSheet open) {
            open.dismissAllowingStateLoss();
        }
        TransactionFormSheet sheet = new TransactionFormSheet();
        sheet.request = request == null ? AppState.FormRequest.create() : request;
        sheet.show(fm, TAG);
    }

    // Pedido de abertura (não sobrevive à recriação da Activity: nesse caso a sheet fecha).
    private AppState.FormRequest request;
    private SheetTransactionFormBinding b;
    private boolean binding;

    // ---------- Estado do formulário ----------
    private String kind = "EXPENSE";
    private Transaction editing;
    private String dueDate = Format.todayIso();
    private String categoryId;
    private String accountId;
    private String toAccountId;
    private boolean paid;
    private String merchant = "";
    private String receiptFile;
    private String receiptAccessKey;
    private final List<TransactionItemInput> items = new ArrayList<>();
    private String scope = TransactionsService.SCOPE_THIS;
    private String invoiceCardId;
    private String invoiceMonth;
    private Map<String, String> errors = new HashMap<>();
    private boolean saving;

    // Comprovante
    private boolean scanning;
    private boolean waitingWorker;
    private boolean deferred;
    private int pollToken;
    private boolean scanBusy;
    private boolean showItems;
    private Bitmap receiptImage;
    private ReceiptScan duplicate;
    private ReceiptScan recurringMatch;
    private String settleOccurrenceId;
    private Uri photoUri;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Fatura
    private TransactionPage invoicePreview;
    private String invoicePreviewKey;
    private boolean showInvoiceItems;

    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (Boolean.TRUE.equals(ok) && photoUri != null) {
                onReceiptPicked(photoUri);
            }
        });
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                onReceiptPicked(uri);
            }
        });
        if (request == null) {
            dismissAllowingStateLoss();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        // Inflado com o contexto da Activity: o seletor de data abre pelo FragmentManager dela.
        b = SheetTransactionFormBinding.inflate(LayoutInflater.from(requireActivity()), container, false);
        return b.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        SheetUtil.expand(getDialog(), true);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        if (request == null) {
            return;
        }
        setupViews();
        init();
        Catalog.live.observe(getViewLifecycleOwner(), c -> {
            b.account.refresh();
            b.fromAccount.refresh();
            b.toAccount.refresh();
            b.category.refresh();
            render();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        pollToken++;
        main.removeCallbacksAndMessages(null);
        b = null;
    }

    private Context ctx() {
        return requireContext();
    }

    private Catalog catalog() {
        return Catalog.get();
    }

    private boolean canScan() {
        User u = ((ContasApp) requireActivity().getApplication()).container().prefs.user();
        // Leitura de comprovante só para contas premium; sem o usuário carregado ainda, mostra (o servidor decide).
        return u == null || u.premium;
    }

    // ---------- Montagem ----------

    private void setupViews() {
        Context c = ctx();
        b.close.setOnClickListener(v -> dismiss());

        boolean allowTransfer = request.editing == null;
        if (allowTransfer) {
            b.kind.setOptions(new String[] {"Despesa", "Receita", "Transferência"}, new String[] {"EXPENSE", "INCOME", "TRANSFER"});
        } else {
            b.kind.setOptions(new String[] {"Despesa", "Receita"}, new String[] {"EXPENSE", "INCOME"});
        }
        b.kind.setOnChange(this::onKind);

        b.amount.setOnChange(v -> {
            if (!binding) {
                b.items.refreshSum();
                renderInvoicePreview();
            }
        });
        b.date.setOnChange(v -> {
            dueDate = v;
            render();
        });
        b.transferDate.setOnChange(v -> {
            dueDate = v;
            render();
        });
        b.account.setOnChange(v -> {
            accountId = v;
            render();
        });
        b.fromAccount.setOnChange(v -> {
            accountId = v;
            render();
        });
        b.toAccount.setOnChange(v -> {
            toAccountId = v;
            render();
        });
        b.category.setOnChange(v -> categoryId = v);
        b.paid.setOnCheckedChangeListener((s, on) -> {
            if (!binding) {
                paid = on;
            }
        });
        b.transferPaid.setOnCheckedChangeListener((s, on) -> {
            if (!binding) {
                paid = on;
            }
        });
        b.invoiceSwitch.setOnCheckedChangeListener((s, on) -> {
            if (!binding) {
                toggleInvoice(on);
            }
        });
        b.invoiceCard.setSheetTitle("Cartão");
        b.invoiceCard.setOnChange(v -> {
            invoiceCardId = v;
            Account card = catalog().account(v);
            if (card != null && invoiceMonth == null) {
                invoiceMonth = nearestInvoiceMonth(card, dueDate != null ? dueDate : Format.todayIso());
            }
            render();
        });
        b.invoiceMonth.setSheetTitle("Fatura");
        b.invoiceMonth.setOnChange(v -> {
            invoiceMonth = v;
            render();
        });
        b.invoicePreviewHeader.setOnClickListener(v -> {
            showInvoiceItems = !showInvoiceItems;
            renderInvoicePreview();
        });

        b.itemsHeader.setOnClickListener(v -> {
            showItems = !showItems;
            render();
        });
        b.items.setAmountSource(() -> b == null ? null : b.amount.value());
        b.items.setOnUseSum(sum -> {
            b.amount.setValue(sum);
            b.items.refreshSum();
        });
        b.items.setOnChange(this::render);

        b.scan.setOnClickListener(v -> pickReceipt());
        b.receiptThumb.setClipToOutline(true);
        b.receiptThumb.setOnClickListener(v -> openReceipt());

        b.recurringMatchToggle.setOptions(new String[] {"Pagar o vencimento", "Criar novo lançamento"}, new String[] {"pay", "new"});
        compact(b.recurringMatchToggle);
        b.recurringMatchToggle.setOnChange(v -> {
            if ("pay".equals(v)) {
                acceptRecurringMatch();
            } else {
                settleOccurrenceId = null;
            }
            render();
        });

        b.scope.setOptions(new String[] {"Só este", "Este e os próximos"},
            new String[] {TransactionsService.SCOPE_THIS, TransactionsService.SCOPE_FOLLOWING});
        compact(b.scope);
        GradientDrawable scopeBg = new GradientDrawable();
        scopeBg.setColor(Ui.color(c, R.color.background));
        scopeBg.setCornerRadius(Ui.dp(c, 12));
        b.scope.setBackground(scopeBg);
        b.scope.setActiveColor(Ui.color(c, R.color.primary), Ui.color(c, R.color.primary_foreground));
        b.scope.setOnChange(v -> {
            scope = v;
            render();
        });
        b.scopeHint.setOnClickListener(v -> {
            FragmentActivity act = requireActivity();
            dismiss();
            try {
                Navigation.findNavController(act, R.id.navHost).navigate(R.id.recurring);
            } catch (RuntimeException e) {
                Log.w("ContasLT", "navegação para recorrentes", e);
            }
        });

        b.delete.setOnClickListener(v -> confirmDelete());
        b.makeRecurring.setOnClickListener(v -> makeRecurring());
        b.save.setOnClickListener(v -> save());
    }

    /** Abas pequenas (text-xs, h-8). */
    private void compact(SegmentedControl seg) {
        for (int i = 0; i < seg.getChildCount(); i++) {
            if (seg.getChildAt(i) instanceof TextView tv) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setMinHeight(Ui.dp(ctx(), 30));
                int p = Ui.dp(ctx(), 8);
                tv.setPadding(p, 0, p, 0);
            }
        }
    }

    /** Valores iniciais: lançamento em edição, padrões do pedido (fatura, data, conta) ou sugestão já preenchida. */
    private void init() {
        Transaction tx = request.editing;
        editing = tx;
        binding = true;
        if (tx != null) {
            kind = tx.type;
            b.amount.setValue(tx.amount);
            b.description.setText(tx.description);
            dueDate = tx.dueDate;
            categoryId = tx.categoryId;
            accountId = tx.accountId;
            paid = tx.paid;
            b.notes.setText(tx.notes == null ? "" : tx.notes);
            toAccountId = null;
            merchant = tx.merchant == null ? "" : tx.merchant;
            receiptFile = tx.receiptFile;
            receiptAccessKey = tx.receiptAccessKey;
            items.clear();
            if (tx.items != null) {
                for (TransactionItem i : tx.items) {
                    items.add(itemInput(i.description, i.quantity, i.unitPrice, i.total));
                }
            }
            showItems = !items.isEmpty();
            invoiceCardId = tx.invoiceCardId;
            invoiceMonth = tx.invoiceMonth;
            loadReceiptImage(tx.receiptFile);
        } else {
            TransactionInput pre = request.prefill;
            kind = request.type != null ? request.type : pre != null && pre.type != null ? pre.type : "EXPENSE";
            Long amount = request.invoiceAmount != null ? request.invoiceAmount : pre != null ? pre.amount : null;
            b.amount.setValue(amount);
            String desc = request.invoiceDescription != null ? request.invoiceDescription : pre != null && pre.description != null ? pre.description : "";
            b.description.setText(desc);
            b.transferDescription.setText("TRANSFER".equals(kind) ? desc : "");
            dueDate = request.dueDate != null ? request.dueDate : pre != null && pre.dueDate != null ? pre.dueDate : Format.todayIso();
            categoryId = pre != null ? pre.categoryId : null;
            accountId = request.accountId != null ? request.accountId : pre != null && pre.accountId != null ? pre.accountId : defaultAccount();
            toAccountId = null;
            invoiceCardId = request.invoiceCardId;
            invoiceMonth = request.invoiceMonth;
            paid = request.invoiceCardId != null || (pre != null && pre.paid);
            merchant = pre != null && pre.merchant != null ? pre.merchant : "";
            receiptFile = null;
            receiptAccessKey = null;
            items.clear();
            showItems = false;
            settleOccurrenceId = request.settleOccurrenceId;
            if ("TRANSFER".equals(kind)) {
                paid = true;
                toAccountId = otherAccount();
            }
        }
        b.kind.setValue(kind);
        b.category.setValue(categoryId);
        b.items.setItems(items);
        binding = false;
        render();
        if (tx == null) {
            b.amount.editText().requestFocus();
        }
    }

    private static TransactionItemInput itemInput(String description, Double quantity, Long unitPrice, long total) {
        TransactionItemInput i = new TransactionItemInput();
        i.description = description;
        i.quantity = quantity;
        i.unitPrice = unitPrice;
        i.total = total;
        return i;
    }

    private String defaultAccount() {
        List<Account> active = catalog().activeAccounts();
        for (Account a : active) {
            if (!"CREDIT_CARD".equals(a.type)) {
                return a.id;
            }
        }
        return active.isEmpty() ? null : active.get(0).id;
    }

    private String otherAccount() {
        for (Account a : catalog().activeAccounts()) {
            if (!a.id.equals(accountId)) {
                return a.id;
            }
        }
        return null;
    }

    private void onKind(String k) {
        String previous = kind;
        kind = k;
        // A descrição é uma só nos dois modos (campos diferentes na tela).
        if ("TRANSFER".equals(k) && !"TRANSFER".equals(previous)) {
            b.transferDescription.setText(b.description.getText());
        } else if (!"TRANSFER".equals(k) && "TRANSFER".equals(previous)) {
            b.description.setText(b.transferDescription.getText());
        }
        Category c = catalog().category(categoryId);
        if (c != null && !c.type.equals(k)) {
            categoryId = null;
            b.category.setValue(null);
        }
        if ("TRANSFER".equals(k)) {
            paid = true;
            if (toAccountId == null) {
                toAccountId = otherAccount();
            }
        }
        render();
    }

    // ---------- Derivados ----------

    private boolean isTransfer() {
        return "TRANSFER".equals(kind);
    }

    /** Lançamento existente que é perna de uma transferência vinculada. */
    private boolean linked() {
        return editing != null && editing.transferId != null;
    }

    private String type() {
        return "INCOME".equals(kind) ? "INCOME" : "EXPENSE";
    }

    private boolean onCard() {
        Account a = catalog().account(accountId);
        return a != null && "CREDIT_CARD".equals(a.type);
    }

    private boolean isInvoice() {
        return invoiceCardId != null;
    }

    private List<Account> cards() {
        List<Account> out = new ArrayList<>();
        for (Account a : catalog().activeAccounts()) {
            if ("CREDIT_CARD".equals(a.type)) {
                out.add(a);
            }
        }
        return out;
    }

    private String description() {
        return (isTransfer() ? b.transferDescription : b.description).getText().toString();
    }

    // ---------- Renderização ----------

    private void render() {
        if (b == null) {
            return;
        }
        binding = true;
        Context c = ctx();
        boolean transfer = isTransfer();
        boolean linked = linked();
        String type = type();
        boolean onCard = onCard();
        boolean invoice = isInvoice();

        b.title.setText(editing != null ? "Editar lançamento" : transfer ? "Nova transferência" : invoice ? "Pagar fatura do cartão"
            : "INCOME".equals(kind) ? "Nova receita" : "Nova despesa");

        // Tipo
        b.kind.setValue(kind);
        int activeBg = switch (kind) {
            case "INCOME" -> Ui.color(c, R.color.income);
            case "TRANSFER" -> Ui.color(c, R.color.primary);
            default -> Ui.color(c, R.color.expense);
        };
        int activeFg = "TRANSFER".equals(kind) ? Ui.color(c, R.color.primary_foreground) : 0xFFFFFFFF;
        b.kind.setActiveColor(activeBg, activeFg);
        boolean kindDisabled = (editing != null && editing.recurringBillId != null) || linked;
        for (int i = 0; i < b.kind.getChildCount(); i++) {
            b.kind.getChildAt(i).setEnabled(!kindDisabled);
        }
        b.kind.setAlpha(kindDisabled ? 0.6f : 1f);

        // Transferência vinculada
        Ui.visible(b.linkedNotice, linked);
        if (linked) {
            boolean out = "EXPENSE".equals(editing.type);
            SpannableStringBuilder s = new SpannableStringBuilder("Parte de uma transferência " + (out ? "para" : "vinda de") + " ");
            int start = s.length();
            s.append(editing.transferPeerAccountName == null ? "" : editing.transferPeerAccountName);
            s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.foreground)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(". Não conta como ").append(out ? "despesa" : "receita").append(". ");
            link(s, c, "Desvincular");
            b.linkedText.setText(s);
            b.linkedText.setOnClickListener(v -> confirmUnlink());
        }

        // Valor
        error(b.amountError, "amount");
        b.amount.setInvalid(errors.containsKey("amount"));

        // Comprovante
        boolean canScan = canScan();
        boolean receiptRow = !transfer && !linked && !invoice && "EXPENSE".equals(kind) && (canScan || receiptFile != null);
        Ui.visible(b.receiptRow, receiptRow);
        Ui.visible(b.scan, canScan);
        b.scan.setEnabled(!scanning);
        b.scan.setText(scanning ? "Lendo comprovante…" : receiptFile != null ? "Trocar comprovante" : "Ler comprovante");
        Ui.visible(b.receiptThumb, receiptImage != null);
        if (receiptImage != null) {
            b.receiptThumb.setImageBitmap(receiptImage);
        }
        Ui.visible(b.receiptHint, receiptImage == null);
        b.receiptHint.setText(scanning ? "Pode levar alguns segundos." : "Foto do cupom: itens e valor preenchidos sozinhos.");
        Ui.visible(b.waiting, waitingWorker && scanning);

        Ui.visible(b.deferred, deferred && !transfer);
        boolean scanNotice = !deferred && editing != null && editing.receiptScanStatus != null
            && Objects.equals(editing.receiptFile, receiptFile) && !transfer;
        Ui.visible(b.scanNotice, scanNotice);
        if (scanNotice) {
            renderScanNotice(c);
        }

        Ui.visible(b.recurringMatch, recurringMatch != null && !transfer);
        if (recurringMatch != null) {
            SpannableStringBuilder s = new SpannableStringBuilder("Parece o pagamento de ");
            MoneyText.strong(s, recurringMatch.recurringMatchDescription == null ? "" : recurringMatch.recurringMatchDescription);
            s.append(", vencimento previsto para ")
                .append(recurringMatch.recurringMatchDate != null ? Format.dateShort(recurringMatch.recurringMatchDate) : "");
            if (recurringMatch.recurringMatchAmount != null) {
                s.append(" (").append(Format.money(recurringMatch.recurringMatchAmount)).append(" estimado)");
            }
            s.append(".");
            if (settleOccurrenceId != null) {
                s.append(" Ao salvar, esse vencimento fica pago com o valor e a data do comprovante.");
            }
            b.recurringMatchText.setText(s);
            b.recurringMatchToggle.setValue(settleOccurrenceId != null ? "pay" : "new");
        }

        Ui.visible(b.duplicate, duplicate != null && !transfer);
        if (duplicate != null) {
            SpannableStringBuilder s = new SpannableStringBuilder();
            MoneyText.strong(s, "Possível duplicata.");
            s.append(" Este comprovante parece já lançado")
                .append(duplicate.duplicateOfDate != null ? " em " + Format.dateShort(duplicate.duplicateOfDate) : "").append(": ");
            MoneyText.strong(s, duplicate.duplicateOfDescription == null ? "" : duplicate.duplicateOfDescription);
            s.append(". Confira antes de salvar.");
            b.duplicateText.setText(s);
        }

        // Seções
        Ui.visible(b.transferSection, transfer);
        Ui.visible(b.normalSection, !transfer);
        if (transfer) {
            b.fromAccount.setValue(accountId);
            b.toAccount.setValue(toAccountId);
            b.transferDate.setValue(dueDate);
            b.transferPaid.setChecked(paid);
            error(b.fromAccountError, "accountId");
            error(b.toAccountError, "toAccountId");
            error(b.transferDateError, "dueDate");
            error(b.transferDescriptionError, "description");
        } else {
            error(b.descriptionError, "description");
            b.description.setBackgroundResource(errors.containsKey("description") ? R.drawable.bg_input_invalid : R.drawable.bg_input);
            b.dateLabel.setText("INCOME".equals(type) ? "Data" : "Vencimento");
            b.date.setValue(dueDate);
            error(b.dateError, "dueDate");
            b.account.setValue(accountId);
            error(b.accountError, "accountId");
            Ui.visible(b.onCardNotice, onCard && "EXPENSE".equals(type));

            List<Account> cards = cards();
            boolean invoiceBox = "EXPENSE".equals(type) && !onCard && !linked && !cards.isEmpty();
            Ui.visible(b.invoiceBox, invoiceBox);
            b.invoiceSwitch.setChecked(invoice);
            Ui.visible(b.invoiceBody, invoice);
            if (invoiceBox && invoice) {
                List<SelectFieldView.Option> co = new ArrayList<>();
                for (Account a : cards) {
                    co.add(SelectFieldView.Option.of(a.id, a.name));
                }
                b.invoiceCard.setOptions(co);
                b.invoiceCard.setValue(invoiceCardId);
                b.invoiceMonth.setOptions(invoiceMonths());
                b.invoiceMonth.setValue(invoiceMonth);
                loadInvoicePreview();
            }
            renderInvoicePreview();

            Ui.visible(b.categoryField, !linked);
            b.category.setType(type);
            error(b.categoryError, "categoryId");

            Ui.visible(b.itemsBox, "EXPENSE".equals(type) && !invoice);
            SpannableStringBuilder it = new SpannableStringBuilder("Itens");
            if (!items.isEmpty()) {
                int start = it.length();
                it.append(" (").append(String.valueOf(items.size())).append(")");
                it.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.muted_foreground)), start, it.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (android.os.Build.VERSION.SDK_INT >= 28) { // TypefaceSpan(Typeface) não existe no Android 8
                    it.setSpan(new android.text.style.TypefaceSpan(android.graphics.Typeface.create(
                        androidx.core.content.res.ResourcesCompat.getFont(c, R.font.manrope), android.graphics.Typeface.NORMAL)),
                        start, it.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            b.itemsTitle.setText(it);
            b.itemsChevron.setRotation(showItems ? 180 : 0);
            Ui.visible(b.itemsBody, showItems);

            b.paidLabel.setText("INCOME".equals(type) ? "Já recebido" : "Já pago");
            b.paid.setChecked(paid);
        }

        // Escopo da ocorrência recorrente
        boolean recurring = editing != null && editing.recurringBillId != null;
        Ui.visible(b.scopeBox, recurring);
        if (recurring) {
            b.scope.setValue(scope);
            SpannableStringBuilder s = new SpannableStringBuilder(TransactionsService.SCOPE_FOLLOWING.equals(scope)
                ? "Valor, descrição, conta e categoria passam para os próximos vencimentos pendentes. Se a data mudar, os próximos seguem o novo dia. \"Pago\" e observações valem só para este. "
                : "Só esta ocorrência muda; os próximos vencimentos continuam como estão. ");
            link(s, c, "Ver recorrentes");
            b.scopeHint.setText(s);
        }

        // Rodapé
        Ui.visible(b.delete, editing != null);
        Ui.visible(b.makeRecurring, editing != null && editing.recurringBillId == null && !linked);
        b.save.setEnabled(!saving);
        b.save.setText(saving ? "Salvando…" : editing != null ? "Salvar" : settleOccurrenceId != null ? "Pagar vencimento"
            : transfer ? "Transferir" : invoice ? "Registrar fatura" : "Adicionar");
        binding = false;
    }

    private static void link(SpannableStringBuilder s, Context c, String text) {
        int start = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.primary)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void error(TextView tv, String key) {
        String e = errors.get(key);
        Ui.visible(tv, e != null);
        tv.setText(e);
    }

    /** ReceiptScanNotice.vue: aguardando, divergente ou falhou. */
    private void renderScanNotice(Context c) {
        String s = editing.receiptScanStatus;
        android.content.res.ColorStateList warn = android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.warning));
        if ("PENDING".equals(s)) {
            b.scanNotice.setBackgroundResource(R.drawable.sheet_transaction_muted_60);
            b.scanNoticeIcon.setImageResource(R.drawable.ic_clock);
            b.scanNoticeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.muted_foreground)));
            b.scanNoticeText.setTextColor(Ui.color(c, R.color.muted_foreground));
            b.scanNoticeText.setText("Comprovante aguardando leitura. Os itens entram sozinhos quando o computador com o leitor estiver ligado.");
            Ui.visible(b.scanNoticeActions, false);
            return;
        }
        b.scanNotice.setBackgroundResource(R.drawable.sheet_transaction_warning);
        b.scanNoticeIcon.setImageResource(R.drawable.ic_triangle_alert);
        b.scanNoticeIcon.setImageTintList(warn);
        b.scanNoticeText.setTextColor(Ui.color(c, R.color.foreground));
        Ui.visible(b.scanNoticeActions, true);
        b.scanNoticePrimary.setEnabled(!scanBusy);
        b.scanNoticeSecondary.setEnabled(!scanBusy);
        SpannableStringBuilder text = new SpannableStringBuilder();
        if ("DIVERGENT".equals(s)) {
            List<String> read = new ArrayList<>();
            List<String> entered = new ArrayList<>();
            if (editing.receiptScanTotal != null) {
                read.add("total " + Format.money(editing.receiptScanTotal));
                entered.add(Format.money(editing.amount));
            }
            if (editing.receiptScanDate != null) {
                read.add("data " + Format.dateShort(editing.receiptScanDate));
                entered.add(Format.dateShort(editing.dueDate));
            }
            MoneyText.strong(text, "Comprovante lido com diferença.");
            text.append(" O cupom diz ").append(String.join(" e ", read)).append("; você lançou ").append(String.join(" em ", entered)).append(".");
            b.scanNoticePrimary.setText("Usar o do comprovante");
            b.scanNoticeSecondary.setText("Manter o lançado");
            b.scanNoticePrimary.setOnClickListener(v -> onScanAction("ACCEPT"));
        } else {
            MoneyText.strong(text, "Não foi possível ler o comprovante.");
            text.append(" A foto continua anexada; tente de novo com o leitor ligado.");
            b.scanNoticePrimary.setText("Tentar de novo");
            b.scanNoticeSecondary.setText("Deixar assim");
            b.scanNoticePrimary.setOnClickListener(v -> onScanAction("RETRY"));
        }
        b.scanNoticeSecondary.setOnClickListener(v -> onScanAction("DISMISS"));
        b.scanNoticeText.setText(text);
    }

    // ---------- Fatura de cartão ----------

    /** Meses de fatura oferecidos: dois antes e um depois do mês da data, com o vencimento de cada um. */
    private List<SelectFieldView.Option> invoiceMonths() {
        String base = (dueDate != null ? dueDate : Format.todayIso()).substring(0, 7);
        Account card = catalog().account(invoiceCardId);
        List<SelectFieldView.Option> out = new ArrayList<>();
        boolean hasCurrent = false;
        for (int i = -2; i <= 1; i++) {
            String m = Format.shiftMonth(base, i);
            String due = card != null && card.dueDay != null ? String.format(Format.PT_BR, " · vence %02d/%s", card.dueDay, m.substring(5, 7)) : "";
            out.add(SelectFieldView.Option.of(m, Format.monthLabel(m).toLowerCase(Format.PT_BR) + due));
            hasCurrent |= m.equals(invoiceMonth);
        }
        if (invoiceMonth != null && !hasCurrent) {
            out.add(0, SelectFieldView.Option.of(invoiceMonth, Format.monthLabel(invoiceMonth).toLowerCase(Format.PT_BR)));
        }
        return out;
    }

    /** Fatura de vencimento mais próximo da data, para sugerir o mês. */
    private static String nearestInvoiceMonth(Account card, String date) {
        String base = date.substring(0, 7);
        if (card.dueDay == null) {
            return base;
        }
        LocalDate target = LocalDate.parse(date);
        String best = base;
        long bestDistance = Long.MAX_VALUE;
        for (int i = -1; i <= 1; i++) {
            String m = Format.shiftMonth(base, i);
            java.time.YearMonth ym = java.time.YearMonth.parse(m);
            LocalDate due = ym.atDay(Math.min(card.dueDay, ym.lengthOfMonth()));
            long distance = Math.abs(ChronoUnit.DAYS.between(target, due));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = m;
            }
        }
        return best;
    }

    private void toggleInvoice(boolean on) {
        if (!on) {
            invoiceCardId = null;
            invoiceMonth = null;
        } else {
            List<Account> cards = cards();
            if (!cards.isEmpty()) {
                invoiceCardId = cards.get(0).id;
                invoiceMonth = nearestInvoiceMonth(cards.get(0), dueDate != null ? dueDate : Format.todayIso());
            }
        }
        render();
    }

    /** Resumo e compras da fatura escolhida, para mostrar o que fica agregado nesta despesa. */
    private void loadInvoicePreview() {
        String key = invoiceCardId + "|" + invoiceMonth;
        if (invoiceCardId == null || invoiceMonth == null || key.equals(invoicePreviewKey)) {
            return;
        }
        invoicePreviewKey = key;
        invoicePreview = null;
        TransactionFilter f = new TransactionFilter();
        f.month = invoiceMonth;
        f.accountId = invoiceCardId;
        f.invoice = true;
        Repo.run(getViewLifecycleOwner(), () -> TransactionsService.listTransactions(f), p -> {
            if (key.equals(invoicePreviewKey)) {
                invoicePreview = p;
                renderInvoicePreview();
            }
        }, e -> invoicePreview = null);
    }

    private void renderInvoicePreview() {
        if (b == null) {
            return;
        }
        boolean show = isInvoice() && invoicePreview != null && invoicePreview.invoice != null
            && (invoiceCardId + "|" + invoiceMonth).equals(invoicePreviewKey);
        Ui.visible(b.invoicePreview, show);
        if (!show) {
            return;
        }
        Context c = ctx();
        var inv = invoicePreview.invoice;
        Long amount = b.amount.value();
        long undetailed = amount != null && amount != 0 ? Math.max(0, amount - inv.purchases) : 0;
        SpannableStringBuilder t = new SpannableStringBuilder(inv.count + " " + (inv.count == 1 ? "compra registrada" : "compras registradas")
            + " · " + Format.money(inv.purchases));
        if (undetailed > 0) {
            int start = t.length();
            t.append(" · não detalhado ").append(Format.money(undetailed));
            t.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.muted_foreground)), start, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        b.invoicePreviewTitle.setText(t);
        b.invoicePreviewChevron.setRotation(showInvoiceItems ? 180 : 0);
        b.invoicePreviewInfo.setText("Compras de " + Format.dateShort(inv.start) + " a " + Format.dateShort(inv.end)
            + " ficam agregadas nesta despesa. Elas não aparecem na lista nem contam de novo.");
        boolean hasItems = !invoicePreview.items.isEmpty();
        Ui.visible(b.invoicePreviewList, showInvoiceItems && hasItems);
        Ui.visible(b.invoicePreviewEmpty, showInvoiceItems && !hasItems);
        b.invoicePreviewList.removeAllViews();
        if (showInvoiceItems && hasItems) {
            for (int i = 0; i < invoicePreview.items.size(); i++) {
                Transaction tx = invoicePreview.items.get(i);
                if (i > 0) {
                    b.invoicePreviewList.addView(Views.divider(b.invoicePreviewList));
                }
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(c, 8);
                row.setPadding(p, Ui.dp(c, 6), p, Ui.dp(c, 6));
                TextView d = new TextView(c, null, 0, R.style.Text_Xs);
                SpannableStringBuilder ds = new SpannableStringBuilder(Format.dateShort(tx.dueDate) + " · " + tx.description);
                if (tx.items != null && !tx.items.isEmpty()) {
                    int start = ds.length();
                    ds.append(" (").append(String.valueOf(tx.items.size())).append(" itens)");
                    ds.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.muted_foreground)), start, ds.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                d.setText(ds);
                d.setMaxLines(1);
                d.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView m = new TextView(c, null, 0, R.style.Text_Semibold);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                Ui.money(m, tx.amount, tx.type, false, false);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.setMarginStart(Ui.dp(c, 8));
                row.addView(m, mlp);
                b.invoicePreviewList.addView(row);
            }
        }
    }

    // ---------- Comprovante ----------

    private void pickReceipt() {
        if (scanning) {
            return;
        }
        new MaterialAlertDialogBuilder(ctx())
            .setTitle("Ler comprovante")
            .setItems(new String[] {"Tirar foto", "Escolher da galeria"}, (d, which) -> {
                if (which == 0) {
                    try {
                        File dir = new File(ctx().getCacheDir(), "receipts");
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                        File file = new File(dir, "comprovante-" + System.currentTimeMillis() + ".jpg");
                        photoUri = FileProvider.getUriForFile(ctx(), ctx().getPackageName() + ".fileprovider", file);
                        takePicture.launch(photoUri);
                    } catch (RuntimeException e) {
                        SheetUtil.snack(b.getRoot(), "Não foi possível abrir a câmera.");
                    }
                } else {
                    pickMedia.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
                }
            })
            .show();
    }

    private void onReceiptPicked(Uri uri) {
        if (b == null) {
            return;
        }
        scanning = true;
        deferred = false;
        duplicate = null;
        recurringMatch = null;
        settleOccurrenceId = null;
        int token = ++pollToken;
        String ignoreId = editing == null ? null : editing.id;
        Context app = ctx().getApplicationContext();
        render();
        Repo.run(getViewLifecycleOwner(), () -> {
            byte[] jpeg = downscale(app, uri);
            return ReceiptsService.scanReceipt(jpeg, "comprovante.jpg", "image/jpeg", ignoreId);
        }, scan -> {
            if (token != pollToken || b == null) {
                return;
            }
            if ("PENDING".equals(scan.status)) {
                // Fila do worker: a foto já está guardada; mostra e espera o computador ler, se ele estiver online.
                kind = "EXPENSE";
                receiptFile = scan.receiptFile;
                receiptAccessKey = null;
                loadReceiptImage(scan.receiptFile);
                if (scan.workerOnline && scan.jobId != null) {
                    waitingWorker = true;
                    render();
                    long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
                    main.postDelayed(() -> poll(scan, token, deadline), POLL_MS);
                } else {
                    deferScan(scan.workerOnline);
                }
                return;
            }
            finishScan(scan);
        }, e -> {
            if (token == pollToken) {
                scanning = false;
                waitingWorker = false;
                render();
                SheetUtil.snack(b == null ? null : b.getRoot(), e.getMessage());
            }
        });
    }

    /** Consulta a tarefa a cada 3 s enquanto o worker responde (até 3 min) e a sheet está aberta. */
    private void poll(ReceiptScan initial, int token, long deadline) {
        if (token != pollToken || b == null) {
            return;
        }
        String ignoreId = editing == null ? null : editing.id;
        Repo.run(getViewLifecycleOwner(), () -> ReceiptsService.receiptJob(initial.jobId, ignoreId), job -> {
            if (token != pollToken || b == null) {
                return;
            }
            if (!"PENDING".equals(job.status)) {
                finishScan(job);
            } else if (job.workerOnline && System.currentTimeMillis() < deadline) {
                main.postDelayed(() -> poll(initial, token, deadline), POLL_MS);
            } else {
                deferScan(initial.workerOnline);
            }
        }, e -> {
            if (token == pollToken) {
                scanning = false;
                waitingWorker = false;
                render();
                SheetUtil.snack(b == null ? null : b.getRoot(), e.getMessage());
            }
        });
    }

    /** A foto ficou na fila: salvar normalmente; os itens chegam depois. */
    private void deferScan(boolean workerOnline) {
        scanning = false;
        waitingWorker = false;
        deferred = true;
        render();
        SheetUtil.snack(b.getRoot(), workerOnline
            ? "O leitor demorou demais. Salve o lançamento; os itens entram quando a leitura terminar."
            : "Leitor offline. Foto guardada: salve o lançamento e os itens entram quando o computador ligar.");
    }

    private void finishScan(ReceiptScan scan) {
        scanning = false;
        waitingWorker = false;
        if ("FAILED".equals(scan.status)) {
            render();
            SheetUtil.snack(b.getRoot(), scan.error != null && !scan.error.isEmpty() ? scan.error
                : "Não foi possível ler o comprovante. A foto continua anexada.");
            return;
        }
        applyScan(scan);
    }

    /** Preenche o formulário com o que o modelo leu. */
    private void applyScan(ReceiptScan scan) {
        String previousKind = kind;
        kind = "EXPENSE";
        if ("TRANSFER".equals(previousKind)) {
            b.description.setText(b.transferDescription.getText());
        }
        receiptFile = scan.receiptFile;
        receiptAccessKey = scan.accessKey;
        merchant = scan.merchant == null ? "" : scan.merchant;
        if (scan.total != null) {
            b.amount.setValue(scan.total);
        }
        if (scan.merchant != null && !scan.merchant.isEmpty() && b.description.getText().toString().trim().isEmpty()) {
            b.description.setText(scan.merchant);
        }
        if (scan.date != null) {
            dueDate = scan.date;
        }
        if (categoryId == null && scan.suggestedCategoryId != null) {
            categoryId = scan.suggestedCategoryId;
            b.category.setValue(categoryId);
        }
        items.clear();
        for (ScanItem i : scan.items) {
            items.add(itemInput(i.description, i.quantity, i.unitPrice, i.total));
        }
        b.items.setItems(items);
        showItems = !items.isEmpty();
        paid = scan.paid;
        loadReceiptImage(scan.receiptFile);
        // Pago no crédito: a compra vai para o cartão, não para a conta corrente.
        String method = scan.paymentMethod == null ? "" : Normalizer.normalize(scan.paymentMethod, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase(Format.PT_BR);
        if (method.contains("credito") && editing == null) {
            List<Account> cards = cards();
            if (!cards.isEmpty()) {
                accountId = cards.get(0).id;
            }
        }
        String message;
        if (scan.duplicateOfTransactionId != null) {
            duplicate = scan;
            message = "Este cupom já foi lançado em " + (scan.duplicateOfDate != null ? Format.dateShort(scan.duplicateOfDate) : "")
                + ": " + scan.duplicateOfDescription;
        } else if (scan.recurringMatchTransactionId != null && editing == null) {
            recurringMatch = scan;
            acceptRecurringMatch();
            message = "Parece o pagamento de " + scan.recurringMatchDescription + ". Confira e salve.";
        } else {
            message = !scan.paid && scan.date != null ? "Fatura lida: vence em " + Format.dateShort(scan.date) + ". Confira os dados."
                : !scan.items.isEmpty() ? "Comprovante lido: " + scan.items.size() + (scan.items.size() == 1 ? " item" : " itens")
                : "Comprovante lido. Confira os dados.";
        }
        render();
        SheetUtil.snack(b.getRoot(), message);
    }

    private void acceptRecurringMatch() {
        ReceiptScan m = recurringMatch;
        if (m == null || m.recurringMatchTransactionId == null) {
            return;
        }
        settleOccurrenceId = m.recurringMatchTransactionId;
        if (m.recurringMatchDescription != null) {
            b.description.setText(m.recurringMatchDescription);
        }
        if (m.recurringMatchCategoryId != null) {
            categoryId = m.recurringMatchCategoryId;
            b.category.setValue(categoryId);
        }
        if (m.recurringMatchAccountId != null) {
            accountId = m.recurringMatchAccountId;
        }
        paid = true;
    }

    /** Aviso de leitura adiada no lançamento em edição: usar o lido, manter o lançado ou ler de novo. */
    private void onScanAction(String action) {
        Transaction tx = editing;
        if (tx == null) {
            return;
        }
        scanBusy = true;
        render();
        Repo.run(getViewLifecycleOwner(), () -> "RETRY".equals(action) && TxActions.retryReceipt != null
            ? TxActions.retryReceipt.apply(tx.id) : TransactionsService.resolveReceiptScan(tx.id, action), updated -> {
            scanBusy = false;
            if ("ACCEPT".equals(action)) {
                b.amount.setValue(updated.amount);
                dueDate = updated.dueDate;
            }
            editing = updated;
            render();
            SheetUtil.snack(b.getRoot(), "ACCEPT".equals(action) ? "Valor e data do comprovante aplicados"
                : "RETRY".equals(action) ? "Comprovante enviado de novo para leitura" : "Aviso removido");
        }, e -> {
            scanBusy = false;
            render();
            SheetUtil.snack(b.getRoot(), e.getMessage());
        });
    }

    private void loadReceiptImage(String file) {
        receiptImage = null;
        if (file == null) {
            return;
        }
        Repo.run(getViewLifecycleOwner(), () -> {
            byte[] bytes = ReceiptsService.downloadReceipt(file);
            return bytes == null ? null : decodeBounded(bytes, MAX_SIDE);
        }, bmp -> {
            if (b != null && Objects.equals(file, receiptFile)) {
                receiptImage = bmp;
                render();
            }
        }, e -> receiptImage = null);
    }

    /** Abre o comprovante em tela cheia (toque para fechar). */
    private void openReceipt() {
        if (receiptImage == null) {
            return;
        }
        Dialog d = new Dialog(ctx(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(ctx());
        iv.setImageBitmap(receiptImage);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        iv.setContentDescription("Comprovante");
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv);
        d.show();
    }

    /** Foto em JPEG com o maior lado até 1600 px, já girada conforme o EXIF (fora da thread principal). */
    private static byte[] downscale(Context c, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new ApiException(-1, "Não foi possível abrir a foto.");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) {
            throw new ApiException(-1, "Não foi possível abrir a foto.");
        }
        int rotation = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int o = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
                rotation = o == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            }
        } catch (Exception ignored) {
            // sem EXIF: mantém a orientação
        }
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f || rotation != 0) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) {
                bmp.recycle();
            }
            bmp = out;
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, os);
        bmp.recycle();
        return os.toByteArray();
    }

    private static Bitmap decodeBounded(byte[] bytes, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    // ---------- Gravação ----------

    private boolean validate() {
        Map<String, String> e = new HashMap<>();
        boolean transfer = isTransfer();
        Long amount = b.amount.value();
        if (amount == null || amount <= 0) {
            e.put("amount", "Informe um valor maior que zero");
        }
        if (!transfer && description().trim().isEmpty()) {
            e.put("description", "Informe a descrição");
        }
        if (dueDate == null) {
            e.put("dueDate", "Informe a data");
        }
        if (accountId == null) {
            e.put("accountId", transfer ? "Escolha a conta de origem" : "Escolha a conta");
        }
        if (transfer) {
            if (toAccountId == null) {
                e.put("toAccountId", "Escolha a conta de destino");
            } else if (toAccountId.equals(accountId)) {
                e.put("toAccountId", "Origem e destino precisam ser diferentes");
            }
        }
        errors = e;
        return e.isEmpty();
    }

    private record Saved(String message, Transaction transaction) {
    }

    private void save() {
        if (saving) {
            return;
        }
        if (!validate()) {
            render();
            return;
        }
        saving = true;
        render();
        boolean transfer = isTransfer();
        String type = type();
        boolean onCard = onCard();
        boolean invoice = isInvoice();
        Long amount = b.amount.value();
        String description = description().trim();
        Transaction target = editing;
        String settle = settleOccurrenceId;
        String scopeValue = target != null && target.recurringBillId != null ? scope : null;

        TransferInput tr = null;
        TransactionInput in = null;
        if (transfer) {
            tr = new TransferInput();
            tr.fromAccountId = accountId;
            tr.toAccountId = toAccountId;
            tr.amount = amount;
            tr.date = dueDate;
            tr.description = description.isEmpty() ? null : description;
            tr.paid = paid;
        } else {
            in = new TransactionInput();
            in.description = description;
            in.amount = amount;
            in.type = type;
            in.dueDate = dueDate;
            in.paid = paid;
            String notes = b.notes.getText().toString().trim();
            in.notes = notes.isEmpty() ? null : notes;
            in.accountId = accountId;
            in.categoryId = categoryId;
            in.merchant = merchant.trim().isEmpty() ? null : merchant.trim();
            in.receiptFile = receiptFile;
            in.receiptAccessKey = receiptAccessKey;
            in.items = new ArrayList<>();
            for (TransactionItemInput i : items) {
                if (i.description != null && !i.description.trim().isEmpty()) {
                    in.items.add(itemInput(i.description.trim(), i.quantity, i.unitPrice, i.total == null ? 0 : i.total));
                }
            }
            in.invoiceCardId = "EXPENSE".equals(type) && !onCard ? invoiceCardId : null;
            in.invoiceMonth = "EXPENSE".equals(type) && !onCard ? invoiceMonth : null;
        }
        TransferInput trf = tr;
        TransactionInput input = in;
        java.util.function.Consumer<Transaction> onSaved = request.onSaved;
        FragmentActivity activity = requireActivity();
        Repo.run(getViewLifecycleOwner(), () -> {
            if (trf != null) {
                Transfer t = TransfersService.createTransfer(trf);
                return new Saved("Transferência registrada", t == null ? null : t.from);
            }
            TransactionsService.SaveResult r;
            String message;
            if (target != null) {
                r = TransactionsService.updateTransaction(target.id, input, scopeValue);
                message = TransactionsService.SCOPE_FOLLOWING.equals(scopeValue) ? "Este e os próximos vencimentos atualizados" : "Lançamento atualizado";
            } else if (settle != null) {
                r = TransactionsService.updateTransaction(settle, input);
                message = "Vencimento pago com o comprovante";
            } else {
                r = TransactionsService.createTransaction(input);
                message = "INCOME".equals(type) ? "Receita adicionada" : invoice ? "Fatura registrada"
                    : onCard ? "Compra no cartão registrada" : "Despesa adicionada";
            }
            if (r.receiptAttached != null) {
                ReceiptsService.attachReceipt(r.transaction.id);
            }
            return new Saved(message, r.transaction);
        }, saved -> {
            saving = false;
            if (onSaved != null) {
                onSaved.accept(saved.transaction());
            }
            dismissAllowingStateLoss();
            SheetUtil.snack(activity, saved.message());
        }, e -> {
            saving = false;
            errors = fieldErrors(e, transfer);
            render();
            SheetUtil.snack(b == null ? null : b.getRoot(), e.getMessage());
        });
    }

    /** Erros por campo da API, com os nomes da transferência trazidos para os campos da tela. */
    private static Map<String, String> fieldErrors(ApiException e, boolean transfer) {
        Map<String, String> out = new HashMap<>();
        if (e.errors == null) {
            return out;
        }
        for (Map.Entry<String, String> en : e.errors.entrySet()) {
            String k = en.getKey();
            if (transfer) {
                k = switch (k) {
                    case "fromAccountId" -> "accountId";
                    case "date" -> "dueDate";
                    default -> k;
                };
            }
            out.put(k, en.getValue());
        }
        return out;
    }

    private void confirmDelete() {
        Transaction target = editing;
        if (target == null) {
            return;
        }
        FragmentActivity activity = requireActivity();
        DialogInterface.OnClickListener ok = (d, w) -> Repo.runAction(getViewLifecycleOwner(), b.getRoot(),
            () -> TransactionsService.deleteTransaction(target.id), () -> {
                dismissAllowingStateLoss();
                SheetUtil.snack(activity, "Lançamento excluído");
            });
        destructive(new MaterialAlertDialogBuilder(ctx())
            .setTitle("Excluir lançamento?")
            .setMessage(linked() ? "A outra parte da transferência continua existindo e volta a contar como lançamento comum."
                : "Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir", ok)
            .show());
    }

    private void confirmUnlink() {
        Transaction target = editing;
        if (target == null || target.transferId == null) {
            return;
        }
        FragmentActivity activity = requireActivity();
        new MaterialAlertDialogBuilder(ctx())
            .setTitle("Desvincular transferência?")
            .setMessage("Os dois lançamentos continuam existindo, mas voltam a contar como despesa e receita.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Desvincular", (d, w) -> Repo.runAction(getViewLifecycleOwner(), b.getRoot(),
                () -> TransfersService.unlinkTransfer(target.transferId), () -> {
                    dismissAllowingStateLoss();
                    SheetUtil.snack(activity, "Transferência desvinculada; os dois lançamentos voltam a contar no resultado");
                }))
            .show();
    }

    private void destructive(androidx.appcompat.app.AlertDialog d) {
        android.widget.Button p = d.getButton(DialogInterface.BUTTON_POSITIVE);
        if (p != null) {
            p.setTextColor(Ui.color(ctx(), R.color.destructive));
        }
    }

    /** Fecha este formulário e abre o de conta recorrente preenchido com o lançamento. */
    private void makeRecurring() {
        Transaction tx = editing;
        if (tx == null) {
            return;
        }
        FragmentManager fm = getParentFragmentManager();
        dismiss();
        RecurringFormSheet.show(fm, null, tx);
    }
}
