package br.com.contaslt.ui.imports;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import br.com.contaslt.AppContainer;
import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentImportBinding;
import br.com.contaslt.databinding.ItemImpBatchBinding;
import br.com.contaslt.databinding.ItemImpRowBinding;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.ConfirmRow;
import br.com.contaslt.dto.ImportBatch;
import br.com.contaslt.dto.ImportConfirmRequest;
import br.com.contaslt.dto.ImportPreview;
import br.com.contaslt.dto.ImportResult;
import br.com.contaslt.dto.PreviewRow;
import br.com.contaslt.dto.User;
import br.com.contaslt.services.ImportsService;
import br.com.contaslt.services.TransfersService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.views.SelectFieldView;
import br.com.contaslt.ui.views.UiKit;

/**
 * Importar extrato (ImportPage.vue): conta e arquivo (OFX ou planilha), revisão linha a linha (seleção, categoria,
 * vínculo com lançamento já feito ou vencimento de recorrente, pagamento de fatura, duplicadas), confirmação e
 * histórico de importações com exclusão.
 */
public class ImportFragment extends Fragment {

    private static final String[] MIME_TYPES = {
        "application/x-ofx", "application/ofx", "application/vnd.intu.qfx", "application/x-qfx",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel.sheet.macroEnabled.12", "text/plain", "application/octet-stream", "*/*",
    };

    /** Linha da revisão: a prévia mais as escolhas do usuário. */
    private static final class ReviewRow {
        final PreviewRow r;
        boolean selected;
        String categoryId;
        /** Quando há um lançamento manual parecido: vincular a ele (true) ou criar um novo (false). */
        boolean link;
        /** Parece pagamento de fatura: registrar como pagamento do cartão escolhido (true) ou como despesa comum. */
        boolean payInvoice;
        String invoiceCard;

        ReviewRow(PreviewRow r) {
            this.r = r;
            selected = !r.duplicate && !r.invoicePaymentReceived;
            categoryId = r.suggestedCategoryId;
            link = r.matchTransactionId != null;
            payInvoice = r.invoiceCardId != null;
            invoiceCard = r.invoiceCardId;
        }

        boolean linking() {
            return link && r.matchTransactionId != null;
        }

        boolean paying() {
            return payInvoice && invoiceCard != null;
        }
    }

    private FragmentImportBinding b;
    private AppContainer app;
    /** "upload" | "review" | "done". */
    private String step = "upload";
    private Uri file;
    private String fileName;
    private long fileSize;
    private boolean busy;
    private ImportPreview preview;
    private final List<ReviewRow> rows = new ArrayList<>();
    private final List<ItemImpRowBinding> rowViews = new ArrayList<>();
    private ImportResult result;
    private int transferHints;

    private final ActivityResultLauncher<String[]> picker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentImportBinding.inflate(inflater, container, false);
        app = ((ContasApp) requireActivity().getApplication()).container();
        Context c = requireContext();

        GradientDrawable drop = new GradientDrawable();
        drop.setCornerRadius(Ui.dp(c, 16));
        drop.setStroke(Ui.dp(c, 2), Ui.color(c, R.color.border), Ui.dp(c, 6), Ui.dp(c, 4));
        b.pick.setBackground(drop);
        b.pick.setOnClickListener(v -> picker.launch(MIME_TYPES));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(UiKit.alpha(Ui.color(c, R.color.primary), 0.1f));
        b.fileIconWrap.setBackground(circle);
        GradientDrawable doneCircle = new GradientDrawable();
        doneCircle.setShape(GradientDrawable.OVAL);
        doneCircle.setColor(UiKit.alpha(Ui.color(c, R.color.income), 0.15f));
        b.doneIconWrap.setBackground(doneCircle);
        b.transferHint.setBackground(UiKit.box(c, UiKit.alpha(Ui.color(c, R.color.primary), 0.05f),
            UiKit.alpha(Ui.color(c, R.color.primary), 0.3f), 12, 1));
        b.confirmBar.setBackground(UiKit.box(c, UiKit.alpha(Ui.color(c, R.color.background), 0.95f), Ui.color(c, R.color.border), 22, 1));
        b.selectAllBar.setBackgroundColor(UiKit.alpha(Ui.color(c, R.color.muted), 0.5f));
        b.rowsCard.setClipToOutline(true);

        Views.section(b.historyTitle, "Importações anteriores", null, null, null);
        b.account.setOnChange(id -> renderUpload());
        b.analyze.setOnClickListener(v -> analyze());
        b.changeFile.setOnClickListener(v -> reset());
        b.selectAll.setOnClickListener(v -> {
            boolean value = b.selectAll.isChecked();
            for (ReviewRow r : rows) {
                r.selected = value;
            }
            for (int i = 0; i < rows.size(); i++) {
                bindRow(rowViews.get(i), rows.get(i));
            }
            renderSummary();
        });
        b.confirm.setOnClickListener(v -> confirm());
        b.importAnother.setOnClickListener(v -> reset());
        b.seeTransactions.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.transactions));
        b.transferHint.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString("cruzar", "1");
            NavHostFragment.findNavController(this).navigate(R.id.transactions, args);
        });
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        Catalog.live.observe(getViewLifecycleOwner(), cat -> {
            b.account.refresh();
            if (b.account.value() == null) {
                List<Account> active = cat.activeAccounts();
                if (!active.isEmpty()) {
                    b.account.setValue(active.get(0).id);
                }
            }
            renderUpload();
        });
        app.session.dataVersion.observe(getViewLifecycleOwner(), v -> loadHistory());
        renderStep();
    }

    // ---------- Passo 1 ----------

    private void onPicked(Uri uri) {
        if (uri == null || b == null) {
            return;
        }
        file = uri;
        fileName = null;
        fileSize = -1;
        try (Cursor cur = requireContext().getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                fileName = cur.getString(0);
                fileSize = cur.isNull(1) ? -1 : cur.getLong(1);
            }
        } catch (RuntimeException ignored) {
            // sem metadados: segue com o nome padrão
        }
        if (fileName == null) {
            fileName = "arquivo";
        }
        renderUpload();
    }

    private void renderUpload() {
        if (b == null) {
            return;
        }
        if (file != null) {
            b.fileIcon.setImageResource(fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".ofx") ? R.drawable.ic_file_text : R.drawable.ic_file_spreadsheet);
            b.fileTitle.setText(fileName);
            b.fileHint.setText((fileSize >= 0 ? String.format(Format.PT_BR, "%.0f KB · ", fileSize / 1024.0) : "") + "toque para trocar");
        } else {
            b.fileIcon.setImageResource(R.drawable.ic_upload);
            b.fileTitle.setText("Solte o arquivo aqui ou toque para escolher");
            b.fileHint.setText("OFX do seu banco ou planilha Excel com colunas Data, Descrição e Valor");
        }
        boolean ok = file != null && b.account.value() != null && !busy;
        b.analyze.setEnabled(ok);
        b.analyze.setAlpha(ok ? 1f : 0.5f);
        b.analyze.setText(busy && "upload".equals(step) ? "Lendo arquivo…" : "Continuar");
    }

    private void loadHistory() {
        Repo.run(getViewLifecycleOwner(), b.getRoot(), ImportsService::importHistory, this::renderHistory);
    }

    private void renderHistory(List<ImportBatch> history) {
        if (b == null) {
            return;
        }
        Ui.visible(b.historySection, !history.isEmpty());
        b.history.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        for (ImportBatch batch : history) {
            if (b.history.getChildCount() > 0) {
                b.history.addView(Views.divider(b.history));
            }
            ItemImpBatchBinding r = ItemImpBatchBinding.inflate(inf, b.history, false);
            r.icon.setImageResource("OFX".equals(batch.source) ? R.drawable.ic_file_text : R.drawable.ic_file_spreadsheet);
            r.filename.setText(batch.filename != null ? batch.filename : "arquivo");
            r.meta.setText(batch.accountName + " · " + dateTime(batch.createdAt));
            r.count.setText(UiKit.plural(batch.importedCount, "lançamento", "lançamentos"));
            r.remove.setOnClickListener(v -> UiKit.confirm(requireContext(), "Excluir importação?",
                "Os " + batch.importedCount + " lançamentos criados por “" + (batch.filename != null ? batch.filename : "este arquivo")
                    + "” serão apagados. As regras de categoria aprendidas continuam.",
                "Excluir tudo", true, () -> removeBatch(batch)));
            b.history.addView(r.getRoot());
        }
    }

    /** "05/11/2026, 13:46" (dateStyle/timeStyle short do pt-BR). */
    private static String dateTime(String iso) {
        if (iso == null) {
            return "";
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm", Format.PT_BR);
        try {
            return Instant.parse(iso).atZone(ZoneId.systemDefault()).format(f);
        } catch (RuntimeException e) {
            try {
                return LocalDateTime.parse(iso).format(f);
            } catch (RuntimeException e2) {
                return iso;
            }
        }
    }

    private void removeBatch(ImportBatch batch) {
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> ImportsService.deleteImport(batch.id), removed ->
            UiKit.toast(getActivity(), "Importação excluída, " + removed + " " + (removed == 1 ? "lançamento removido" : "lançamentos removidos")));
    }

    private static byte[] readAll(ContentResolver cr, Uri uri) throws java.io.IOException {
        try (InputStream in = cr.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new java.io.IOException("Não foi possível ler o arquivo");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void analyze() {
        String accountId = b.account.value();
        if (file == null || accountId == null || busy) {
            return;
        }
        busy = true;
        renderUpload();
        ContentResolver cr = requireContext().getContentResolver();
        Uri uri = file;
        String name = fileName;
        Repo.run(getViewLifecycleOwner(), () -> ImportsService.previewImport(accountId, name, readAll(cr, uri)), p -> {
            busy = false;
            preview = p;
            rows.clear();
            for (PreviewRow r : p.rows) {
                rows.add(new ReviewRow(r));
            }
            step = "review";
            renderStep();
            renderReview();
        }, e -> {
            busy = false;
            renderUpload();
            UiKit.toast(getActivity(), e.getMessage());
        });
    }

    // ---------- Passo 2 ----------

    private void renderStep() {
        Ui.visible(b.upload, "upload".equals(step));
        Ui.visible(b.review, "review".equals(step));
        Ui.visible(b.confirmBar, "review".equals(step));
        Ui.visible(b.done, "done".equals(step));
        b.scroll.scrollTo(0, 0);
    }

    private void renderReview() {
        b.reviewFile.setText(preview.filename);
        b.rows.removeAllViews();
        rowViews.clear();
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                b.rows.addView(Views.divider(b.rows));
            }
            ItemImpRowBinding v = ItemImpRowBinding.inflate(inf, b.rows, false);
            setupRow(v, rows.get(i));
            bindRow(v, rows.get(i));
            rowViews.add(v);
            b.rows.addView(v.getRoot());
        }
        renderSummary();
    }

    private List<Account> cards() {
        List<Account> out = new ArrayList<>();
        for (Account a : Catalog.get().activeAccounts()) {
            if ("CREDIT_CARD".equals(a.type)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Liga os controles da linha uma vez; {@link #bindRow} atualiza o que aparece. */
    private void setupRow(ItemImpRowBinding v, ReviewRow row) {
        Context c = requireContext();
        PreviewRow r = row.r;
        v.date.setText(Format.dateNumeric(r.date));
        Ui.money(v.amount, r.amount, r.type, true, false);
        v.description.setText(r.description);
        v.selected.setContentDescription("Importar " + r.description);
        v.selected.setOnClickListener(x -> {
            row.selected = v.selected.isChecked();
            bindRow(v, row);
            renderSummary();
        });
        v.category.setType(r.type);
        v.category.setValue(row.categoryId);
        v.category.setOnChange(id -> {
            row.categoryId = id;
            bindRow(v, row);
        });
        GradientDrawable dashed = new GradientDrawable();
        dashed.setCornerRadius(Ui.dp(c, 10));
        dashed.setStroke(Ui.dp(c, 1), Ui.color(c, R.color.border), Ui.dp(c, 4), Ui.dp(c, 3));
        v.tag.setBackground(dashed);
        v.invoicePanel.setBackground(UiKit.primarySoft(c));
        v.matchPanel.setBackground(UiKit.primarySoft(c));
        v.invoiceMode.setOptions(new String[] {"Pagar fatura", "Despesa comum"}, new String[] {"pay", "expense"});
        v.invoiceMode.setOnChange(m -> {
            row.payInvoice = "pay".equals(m);
            bindRow(v, row);
            renderSummary();
        });
        List<SelectFieldView.Option> cardOpts = new ArrayList<>();
        for (Account a : cards()) {
            cardOpts.add(SelectFieldView.Option.dot(a.id, a.name, a.color));
        }
        v.invoiceCard.setOptions(cardOpts);
        v.invoiceCard.setSheetTitle("Cartão");
        v.invoiceCard.setOnChange(id -> {
            row.invoiceCard = id;
            bindRow(v, row);
            renderSummary();
        });
        v.matchMode.setOptions(new String[] {"Vincular", "Criar novo"}, new String[] {"link", "new"});
        v.matchMode.setOnChange(m -> {
            row.link = "link".equals(m);
            bindRow(v, row);
            renderSummary();
        });
    }

    private void bindRow(ItemImpRowBinding v, ReviewRow row) {
        Context c = requireContext();
        PreviewRow r = row.r;
        v.selected.setChecked(row.selected);
        v.main.setAlpha(row.selected ? 1f : 0.6f);

        if (r.duplicate) {
            v.status.setText("Já importado");
            v.status.setTextColor(Ui.color(c, R.color.warning));
            v.status.setTypeface(v.status.getTypeface(), android.graphics.Typeface.BOLD);
            Ui.visible(v.status, true);
        } else if (r.invoicePaymentReceived) {
            v.status.setText("Pagamento da fatura · não importar");
            v.status.setTextColor(Ui.color(c, R.color.muted_foreground));
            v.status.setTypeface(v.status.getTypeface(), android.graphics.Typeface.BOLD);
            v.status.setTooltipText("O pagamento da fatura já é uma despesa na conta que pagou; aqui ele não é receita nem estorno");
            Ui.visible(v.status, true);
        } else if (r.suggestedCategoryId != null && r.suggestedCategoryId.equals(row.categoryId)) {
            v.status.setText("Categoria sugerida");
            v.status.setTextColor(Ui.color(c, R.color.muted_foreground));
            Ui.visible(v.status, true);
        } else {
            Ui.visible(v.status, false);
        }

        boolean linking = row.linking();
        boolean paying = !linking && row.paying();
        Ui.visible(v.tag, linking || paying);
        Ui.visible(v.category, !linking && !paying);
        if (linking) {
            v.tagIcon.setImageResource(r.matchRecurring ? R.drawable.ic_repeat : R.drawable.ic_link_2);
            v.tagText.setText(r.matchRecurring ? "Paga o vencimento" : r.matchCategoryName != null ? r.matchCategoryName : "Mantém o lançamento");
        } else if (paying) {
            v.tagIcon.setImageResource(R.drawable.ic_credit_card);
            v.tagText.setText("Paga a fatura");
        }

        boolean invoicePanel = r.invoiceCardId != null && r.matchTransactionId == null && row.selected;
        Ui.visible(v.invoicePanel, invoicePanel);
        if (invoicePanel) {
            Account card = Catalog.get().account(row.invoiceCard);
            SpannableStringBuilder s = new SpannableStringBuilder("Parece o pagamento da fatura de ");
            UiKit.bold(s, card != null ? card.name : r.invoiceCardName != null ? r.invoiceCardName : "");
            if (r.invoiceDueDate != null) {
                s.append(" que vence em ").append(Format.dateNumeric(r.invoiceDueDate));
            }
            if (r.invoiceTotal != null) {
                s.append(" (").append(Format.money(r.invoiceTotal)).append(" em compras registradas)");
            }
            s.append(".\nVira a despesa da fatura, com as compras do cartão desse período agregadas dentro dela; o que exceder as compras registradas aparece como \"não detalhado\".");
            v.invoiceText.setText(s);
            v.invoiceMode.setValue(row.payInvoice ? "pay" : "expense");
            boolean pickCard = cards().size() > 1 && row.payInvoice;
            Ui.visible(v.invoiceCard, pickCard);
            if (pickCard) {
                v.invoiceCard.setValue(row.invoiceCard);
            }
        }

        boolean matchPanel = r.matchTransactionId != null && row.selected;
        Ui.visible(v.matchPanel, matchPanel);
        if (matchPanel) {
            v.matchIcon.setImageResource(r.matchRecurring ? R.drawable.ic_repeat : r.matchHasReceipt ? R.drawable.ic_receipt : R.drawable.ic_link_2);
            SpannableStringBuilder s = new SpannableStringBuilder();
            String date = r.matchDate != null ? Format.dateNumeric(r.matchDate) : "";
            if (r.matchRecurring) {
                s.append("Parece o vencimento de ");
                UiKit.bold(s, r.matchDescription == null ? "" : r.matchDescription);
                s.append(" previsto para ").append(date);
                if (r.matchAmount != null && r.matchAmount != r.amount) {
                    s.append(" (").append(Format.money(r.matchAmount)).append(" estimado)");
                }
                s.append(". Vincular marca como pago com o valor e a data do extrato.");
            } else {
                s.append("Você já lançou ");
                UiKit.bold(s, r.matchDescription == null ? "" : r.matchDescription);
                s.append(" em ").append(date);
                if (r.matchItemCount > 0) {
                    s.append(" (").append(UiKit.plural(r.matchItemCount, "item", "itens")).append(")");
                }
            }
            v.matchText.setText(s);
            v.matchMode.setValue(row.link ? "link" : "new");
        }
    }

    private void renderSummary() {
        int selected = 0;
        int linked = 0;
        int invoices = 0;
        long total = 0;
        boolean all = !rows.isEmpty();
        for (ReviewRow r : rows) {
            if (!r.selected) {
                all = false;
                continue;
            }
            selected++;
            if (r.linking()) {
                linked++;
            }
            if (r.paying()) {
                invoices++;
            }
            total += "INCOME".equals(r.r.type) ? r.r.amount : -r.r.amount;
        }
        b.selectAll.setChecked(all);
        StringBuilder s = new StringBuilder(UiKit.plural(preview.total, "lançamento encontrado", "lançamentos encontrados"));
        if (preview.duplicates > 0) {
            s.append(", ").append(preview.duplicates).append(" já importados (desmarcados)");
        }
        if (linked > 0) {
            s.append(", ").append(linked).append(" ").append(linked == 1 ? "será vinculado a um lançamento seu" : "serão vinculados a lançamentos seus");
        }
        if (invoices > 0) {
            s.append(", ").append(invoices).append(" ").append(invoices == 1 ? "pagamento de fatura" : "pagamentos de fatura");
        }
        b.reviewSummary.setText(s);

        Context c = requireContext();
        SpannableStringBuilder sel = new SpannableStringBuilder();
        UiKit.bold(sel, String.valueOf(selected));
        sel.append(" ").append(selected == 1 ? "selecionado" : "selecionados").append("  ");
        int start = sel.length();
        String money = Format.money(total);
        sel.append(money);
        sel.setSpan(new android.text.style.AbsoluteSizeSpan(12, true), start, sel.length(), 0);
        sel.setSpan(new android.text.style.ForegroundColorSpan(Ui.color(c, total < 0 ? R.color.expense : R.color.foreground)), start, sel.length(), 0);
        b.selectedText.setText(sel);
        boolean ok = selected > 0 && !busy;
        b.confirm.setEnabled(ok);
        b.confirm.setAlpha(ok ? 1f : 0.5f);
        b.confirm.setText(busy ? "Importando…" : "Importar " + selected);
    }

    private void confirm() {
        if (preview == null || busy) {
            return;
        }
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.accountId = preview.accountId;
        req.source = preview.source;
        req.filename = preview.filename;
        for (ReviewRow row : rows) {
            if (!row.selected) {
                continue;
            }
            PreviewRow r = row.r;
            boolean linking = row.linking();
            boolean paying = !linking && row.paying();
            ConfirmRow cr = new ConfirmRow();
            cr.date = r.date;
            cr.description = r.description;
            cr.amount = r.amount;
            cr.type = r.type;
            cr.categoryId = linking || paying ? null : row.categoryId;
            cr.externalId = r.externalId;
            cr.paid = true;
            cr.learnRule = !linking && !paying && row.categoryId != null;
            cr.linkTransactionId = linking ? r.matchTransactionId : null;
            cr.invoiceCardId = paying ? row.invoiceCard : null;
            cr.invoiceMonth = paying ? r.invoiceMonth : null;
            req.rows.add(cr);
        }
        if (req.rows.isEmpty()) {
            return;
        }
        busy = true;
        renderSummary();
        User user = app.prefs.user();
        String userName = user != null ? user.name : null;
        Repo.run(getViewLifecycleOwner(), () -> {
            ImportResult res = ImportsService.confirmImport(req);
            int hints;
            try {
                hints = TransfersService.transferSuggestions(userName).items.size();
            } catch (RuntimeException e) {
                hints = 0;
            }
            return new Object[] {res, hints};
        }, out -> {
            busy = false;
            result = (ImportResult) out[0];
            transferHints = (Integer) out[1];
            step = "done";
            renderStep();
            renderDone();
            ImportResult r = result;
            UiKit.toast(getActivity(), r.linked > 0
                ? r.imported + " importados, " + r.linked + " " + (r.linked == 1 ? "vinculado" : "vinculados")
                : r.invoicePayments > 0
                    ? r.imported + " importados, " + r.invoicePayments + " " + (r.invoicePayments == 1 ? "pagamento de fatura" : "pagamentos de fatura")
                    : r.imported + " lançamentos importados");
        }, e -> {
            busy = false;
            renderSummary();
            UiKit.toast(getActivity(), e.getMessage());
        });
    }

    // ---------- Passo 3 ----------

    private void renderDone() {
        ImportResult r = result;
        StringBuilder s = new StringBuilder(UiKit.plural(r.imported, "lançamento importado", "lançamentos importados"));
        if (r.invoicePayments > 0) {
            s.append(", ").append(r.invoicePayments).append(" ")
                .append(r.invoicePayments == 1 ? "registrado como pagamento de fatura" : "registrados como pagamentos de fatura");
        }
        if (r.linked > 0) {
            s.append(", ").append(r.linked).append(" ")
                .append(r.linked == 1 ? "vinculado a um lançamento que você já tinha" : "vinculados a lançamentos que você já tinha");
        }
        if (r.skipped > 0) {
            s.append(", ").append(r.skipped).append(" ignorados por já existirem");
        }
        s.append(". As categorias escolhidas serão sugeridas nas próximas importações.");
        b.doneText.setText(s);
        Ui.visible(b.transferHint, transferHints > 0);
        if (transferHints > 0) {
            SpannableStringBuilder h = new SpannableStringBuilder(transferHints + " "
                + (transferHints == 1 ? "possível transferência entre contas encontrada" : "possíveis transferências entre contas encontradas") + ". ");
            UiKit.colored(h, "Revisar", Ui.color(requireContext(), R.color.primary), true);
            b.transferHintText.setText(h);
        }
    }

    private void reset() {
        step = "upload";
        file = null;
        fileName = null;
        preview = null;
        rows.clear();
        rowViews.clear();
        b.rows.removeAllViews();
        result = null;
        renderStep();
        renderUpload();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
