package br.com.contaslt.report;

import java.time.YearMonth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.contaslt.common.BusinessException;
import br.com.contaslt.report.ReportDtos.CategoryReport;
import br.com.contaslt.report.ReportDtos.ItemReport;
import br.com.contaslt.user.CurrentUser;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final int DEFAULT_ITEM_MONTHS = 12;

    private final ReportService reports;
    private final CurrentUser currentUser;

    public ReportController(ReportService reports, CurrentUser currentUser) {
        this.reports = reports;
        this.currentUser = currentUser;
    }

    @GetMapping("/categories")
    public CategoryReport categories(@RequestParam(required = false) String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        return reports.byCategory(currentUser.get(), ym);
    }

    /** Sem {@code from}/{@code to}: os últimos 12 meses até o mês atual. */
    @GetMapping("/items")
    public ItemReport items(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String search) {
        YearMonth end = to == null || to.isBlank() ? YearMonth.now() : YearMonth.parse(to);
        YearMonth start = from == null || from.isBlank() ? end.minusMonths(DEFAULT_ITEM_MONTHS - 1) : YearMonth.parse(from);
        if (start.isAfter(end)) {
            throw new BusinessException("O mês inicial não pode ser depois do final");
        }
        return reports.byItem(currentUser.get(), start, end, categoryId, search);
    }
}
