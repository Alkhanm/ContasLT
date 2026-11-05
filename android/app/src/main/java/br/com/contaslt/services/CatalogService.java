package br.com.contaslt.services;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import br.com.contaslt.data.LocalStore;
import br.com.contaslt.data.schema.AccountRec;
import br.com.contaslt.data.schema.CategoryRec;
import br.com.contaslt.data.schema.CategoryRuleRec;
import br.com.contaslt.data.schema.Entity;
import br.com.contaslt.data.schema.TransactionRec;
import br.com.contaslt.domain.Dates;
import br.com.contaslt.domain.Ids;
import br.com.contaslt.domain.Text;
import br.com.contaslt.dto.Account;
import br.com.contaslt.dto.AccountInput;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.CategoryInput;
import br.com.contaslt.dto.CategoryRule;

/**
 * Contas, categorias e regras de categorização (porte de frontend/src/services/catalog.ts).
 */
public final class CatalogService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    /** Ordem alfabética pt-BR (Intl.Collator('pt-BR') do PWA). */
    public static final Comparator<String> COLLATOR = collator();

    private CatalogService() {
    }

    private static Comparator<String> collator() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        return (a, b) -> {
            synchronized (c) {
                return c.compare(a, b);
            }
        };
    }

    // ---------- Contas ----------

    public static List<Account> listAccounts() {
        return listAccounts(false);
    }

    public static List<Account> listAccounts(boolean includeArchived) {
        LocalStore s = Ctx.db();
        List<TransactionRec> txs = s.list(Entity.TRANSACTION);
        Map<String, Long> movements = Mappers.paidMovementsByAccount(txs);
        List<AccountRec> accounts = new ArrayList<>();
        for (AccountRec a : s.list(Entity.ACCOUNT)) {
            if (includeArchived || !a.archived) {
                accounts.add(a);
            }
        }
        accounts.sort((a, b) -> {
            int byArchived = Boolean.compare(a.archived, b.archived);
            return byArchived != 0 ? byArchived : COLLATOR.compare(a.name, b.name);
        });
        List<Account> out = new ArrayList<>();
        for (AccountRec a : accounts) {
            out.add(Mappers.accountDto(a, Mappers.accountBalance(a, movements, txs)));
        }
        return out;
    }

    public static Account accountById(String id) {
        AccountRec a = Ctx.require(Ctx.db().get(Entity.ACCOUNT, id), "Conta não encontrada");
        List<TransactionRec> txs = Ctx.db().list(Entity.TRANSACTION);
        return Mappers.accountDto(a, Mappers.accountBalance(a, Mappers.paidMovementsByAccount(txs), txs));
    }

    private static boolean validDay(Integer d) {
        return d == null || (d >= 1 && d <= 28);
    }

    private static void applyAccount(AccountRec a, AccountInput req) {
        String name = req.name == null ? "" : Text.trim(req.name);
        Ctx.validate()
            .check("name", !name.isEmpty(), "Informe o nome da conta")
            .check("name", name.length() <= 80, "Nome muito longo")
            .check("type", Text.present(req.type), "Informe o tipo")
            .check("color", req.color == null || COLOR.matcher(req.color).matches(), "Cor inválida")
            .check("closingDay", validDay(req.closingDay), "Use um dia entre 1 e 28")
            .check("dueDay", validDay(req.dueDay), "Use um dia entre 1 e 28")
            .throwIfAny();
        a.name = name;
        a.type = req.type;
        a.initialBalance = req.initialBalance;
        if (Text.present(req.color)) {
            a.color = req.color;
        }
        if (req.archived != null) {
            a.archived = req.archived;
        }
        if ("CREDIT_CARD".equals(req.type)) {
            if ((req.closingDay == null) != (req.dueDay == null)) {
                throw new Ctx.BusinessException("Informe o dia de fechamento e o de vencimento da fatura");
            }
            if (req.closingDay != null && req.closingDay.equals(req.dueDay)) {
                throw new Ctx.BusinessException("Fechamento e vencimento precisam ser dias diferentes");
            }
            a.closingDay = req.closingDay;
            a.dueDay = req.dueDay;
        } else {
            a.closingDay = null;
            a.dueDay = null;
        }
    }

    public static Account createAccount(AccountInput req) {
        AccountRec a = new AccountRec();
        a.id = Ids.newId();
        a.name = "";
        a.type = "CHECKING";
        a.initialBalance = 0;
        a.color = "#1B5E3F";
        a.archived = false;
        a.closingDay = null;
        a.dueDay = null;
        a.createdAt = Dates.nowIso();
        applyAccount(a, req);
        Ctx.db().put(Entity.ACCOUNT, a);
        return accountById(a.id);
    }

    public static Account updateAccount(String id, AccountInput req) {
        AccountRec a = Ctx.require(Ctx.db().get(Entity.ACCOUNT, id), "Conta não encontrada").copy();
        applyAccount(a, req);
        Ctx.db().put(Entity.ACCOUNT, a);
        return accountById(id);
    }

    public static void deleteAccount(String id) {
        Ctx.require(Ctx.db().get(Entity.ACCOUNT, id), "Conta não encontrada");
        for (TransactionRec t : Ctx.db().list(Entity.TRANSACTION)) {
            if (id.equals(t.accountId)) {
                throw new Ctx.BusinessException("Esta conta possui lançamentos. Arquive-a em vez de excluir.");
            }
        }
        Ctx.db().remove(Entity.ACCOUNT, id);
    }

    // ---------- Categorias ----------

    private static final String[][] DEFAULT_CATEGORIES = {
        {"Moradia", "EXPENSE", "#2F6B4F", "house"},
        {"Contas da casa", "EXPENSE", "#3D8361", "plug-zap"},
        {"Mercado", "EXPENSE", "#C98A2B", "shopping-cart"},
        {"Alimentação", "EXPENSE", "#D9622B", "utensils"},
        {"Transporte", "EXPENSE", "#3A6EA5", "car"},
        {"Saúde", "EXPENSE", "#C2452D", "heart-pulse"},
        {"Educação", "EXPENSE", "#6B4FBB", "graduation-cap"},
        {"Assinaturas", "EXPENSE", "#8A5CF6", "repeat"},
        {"Lazer", "EXPENSE", "#E0A526", "popcorn"},
        {"Vestuário", "EXPENSE", "#B5537A", "shirt"},
        {"Impostos e taxas", "EXPENSE", "#6B7280", "landmark"},
        {"Cartão de crédito", "EXPENSE", "#4B5563", "credit-card"},
        {"Outras despesas", "EXPENSE", "#9CA3AF", "circle-ellipsis"},
        {"Salário", "INCOME", "#1B5E3F", "briefcase"},
        {"Freelance", "INCOME", "#2E8B57", "laptop"},
        {"Investimentos", "INCOME", "#0F766E", "trending-up"},
        {"Outras receitas", "INCOME", "#6B7280", "circle-plus"},
    };

    /** Conta nova: categorias padrão e uma "Conta principal" (antes feito no cadastro, no servidor). */
    public static void seedDefaults() {
        LocalStore s = Ctx.db();
        String createdAt = Dates.nowIso();
        for (String[] d : DEFAULT_CATEGORIES) {
            CategoryRec c = new CategoryRec();
            c.id = Ids.newId();
            c.name = d[0];
            c.type = d[1];
            c.color = d[2];
            c.icon = d[3];
            c.archived = false;
            c.parentId = null;
            c.createdAt = createdAt;
            s.put(Entity.CATEGORY, c);
        }
        AccountRec a = new AccountRec();
        a.id = Ids.newId();
        a.name = "Conta principal";
        a.type = "CHECKING";
        a.initialBalance = 0;
        a.color = "#1B5E3F";
        a.archived = false;
        a.closingDay = null;
        a.dueDay = null;
        a.createdAt = createdAt;
        s.put(Entity.ACCOUNT, a);
    }

    private static int sortCategories(CategoryRec a, CategoryRec b) {
        int byType = a.type.compareTo(b.type);
        return byType != 0 ? byType : COLLATOR.compare(a.name, b.name);
    }

    public static List<Category> listCategories() {
        return listCategories(false);
    }

    public static List<Category> listCategories(boolean includeArchived) {
        List<CategoryRec> list = new ArrayList<>();
        for (CategoryRec c : Ctx.db().list(Entity.CATEGORY)) {
            if (includeArchived || !c.archived) {
                list.add(c);
            }
        }
        list.sort(CatalogService::sortCategories);
        List<Category> out = new ArrayList<>();
        for (CategoryRec c : list) {
            out.add(Mappers.categoryDto(c));
        }
        return out;
    }

    public static CategoryRec requireCategory(String id) {
        return Ctx.require(Ctx.db().get(Entity.CATEGORY, id), "Categoria não encontrada");
    }

    public static CategoryRec requireCategoryOrNull(String id) {
        return id == null ? null : requireCategory(id);
    }

    public static List<CategoryRec> childrenOf(String id) {
        List<CategoryRec> out = new ArrayList<>();
        for (CategoryRec c : Ctx.db().list(Entity.CATEGORY)) {
            if (id.equals(c.parentId)) {
                out.add(c);
            }
        }
        return out;
    }

    /** A categoria e as subcategorias dela: filtrar pelo pai inclui as filhas. */
    public static List<String> idsWithChildren(String id) {
        CategoryRec c = requireCategory(id);
        List<String> out = new ArrayList<>();
        out.add(c.id);
        for (CategoryRec x : childrenOf(c.id)) {
            out.add(x.id);
        }
        return out;
    }

    /** Categoria de primeiro nível (a própria, ou o pai de uma subcategoria). */
    public static CategoryRec rootOf(CategoryRec c) {
        if (Text.present(c.parentId)) {
            CategoryRec parent = Ctx.db().get(Entity.CATEGORY, c.parentId);
            if (parent != null) {
                return parent;
            }
        }
        return c;
    }

    private static CategoryRec resolveParent(CategoryRec current, String parentId) {
        if (parentId == null) {
            return null;
        }
        if (current != null && parentId.equals(current.id)) {
            throw new Ctx.BusinessException("Uma categoria não pode ser subcategoria dela mesma");
        }
        CategoryRec parent = requireCategory(parentId);
        if (parent.parentId != null) {
            throw new Ctx.BusinessException("\"" + parent.name + "\" já é uma subcategoria. Só há um nível de subcategorias.");
        }
        if (current != null && !childrenOf(current.id).isEmpty()) {
            throw new Ctx.BusinessException("\"" + current.name + "\" tem subcategorias e não pode virar subcategoria.");
        }
        return parent;
    }

    private static void applyCategory(CategoryRec c, CategoryInput req, CategoryRec parent) {
        String name = req.name == null ? "" : Text.trim(req.name);
        Ctx.validate()
            .check("name", !name.isEmpty(), "Informe o nome da categoria")
            .check("name", name.length() <= 60, "Nome muito longo")
            .check("type", Text.present(req.type), "Informe o tipo")
            .check("color", Text.present(req.color), "Escolha uma cor")
            .check("color", !Text.present(req.color) || COLOR.matcher(req.color).matches(), "Cor inválida")
            .throwIfAny();
        c.name = name;
        c.parentId = parent == null ? null : parent.id;
        // Subcategoria sempre tem o tipo do pai.
        c.type = parent != null ? parent.type : req.type;
        c.color = req.color;
        c.icon = req.icon != null && !Text.trim(req.icon).isEmpty() ? req.icon : "tag";
        if (req.archived != null) {
            c.archived = req.archived;
        }
    }

    public static Category createCategory(CategoryInput req) {
        CategoryRec c = new CategoryRec();
        c.id = Ids.newId();
        c.name = "";
        c.type = "EXPENSE";
        c.color = "";
        c.icon = "tag";
        c.archived = false;
        c.parentId = null;
        c.createdAt = Dates.nowIso();
        applyCategory(c, req, resolveParent(null, req.parentId));
        Ctx.db().put(Entity.CATEGORY, c);
        return Mappers.categoryDto(c);
    }

    public static Category updateCategory(String id, CategoryInput req) {
        LocalStore s = Ctx.db();
        CategoryRec c = requireCategory(id).copy();
        applyCategory(c, req, resolveParent(c, req.parentId));
        s.put(Entity.CATEGORY, c);
        CategoryRec parent = Text.present(c.parentId) ? s.get(Entity.CATEGORY, c.parentId) : null;
        if (!c.archived && parent != null && parent.archived) {
            CategoryRec p = parent.copy();
            p.archived = false;
            s.put(Entity.CATEGORY, p);
        }
        for (CategoryRec child : childrenOf(id)) {
            CategoryRec next = child.copy();
            next.type = c.type;
            next.archived = c.archived || child.archived;
            if (!Objects.equals(next.type, child.type) || next.archived != child.archived) {
                s.put(Entity.CATEGORY, next);
            }
        }
        return Mappers.categoryDto(c);
    }

    public static void deleteCategory(String id) {
        requireCategory(id);
        if (!childrenOf(id).isEmpty()) {
            throw new Ctx.BusinessException("Esta categoria tem subcategorias. Exclua ou mova as subcategorias antes.");
        }
        for (TransactionRec t : Ctx.db().list(Entity.TRANSACTION)) {
            if (id.equals(t.categoryId)) {
                throw new Ctx.BusinessException("Esta categoria está em uso. Arquive-a em vez de excluir.");
            }
        }
        Ctx.db().remove(Entity.CATEGORY, id);
        for (CategoryRuleRec r : Ctx.db().list(Entity.CATEGORY_RULE)) {
            if (id.equals(r.categoryId)) {
                Ctx.db().remove(Entity.CATEGORY_RULE, r.id);
            }
        }
    }

    // ---------- Regras de categorização ----------

    public static List<CategoryRule> listRules() {
        List<CategoryRuleRec> rules = new ArrayList<>(Ctx.db().list(Entity.CATEGORY_RULE));
        rules.sort((a, b) -> a.keyword.compareTo(b.keyword));
        List<CategoryRule> out = new ArrayList<>();
        for (CategoryRuleRec r : rules) {
            CategoryRule d = Mappers.ruleDto(r);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /** Cria a regra, ou troca a categoria de uma regra com a mesma palavra-chave. */
    public static CategoryRuleRec saveRule(String keyword, String categoryId) {
        CategoryRec category = requireCategory(categoryId);
        String key = Text.truncate(Text.normalize(keyword), 120);
        Ctx.validate().check("keyword", !key.isEmpty(), "Informe a palavra-chave").throwIfAny();
        CategoryRuleRec existing = null;
        for (CategoryRuleRec r : Ctx.db().list(Entity.CATEGORY_RULE)) {
            if (key.equals(r.keyword)) {
                existing = r;
                break;
            }
        }
        CategoryRuleRec rule;
        if (existing != null) {
            rule = existing.copy();
            rule.categoryId = category.id;
        } else {
            rule = new CategoryRuleRec();
            rule.id = Ids.newId();
            rule.categoryId = category.id;
            rule.keyword = key;
            rule.createdAt = Dates.nowIso();
        }
        if (existing == null || !category.id.equals(existing.categoryId)) {
            Ctx.db().put(Entity.CATEGORY_RULE, rule);
        }
        return rule;
    }

    public static CategoryRule createRule(String keyword, String categoryId) {
        return Mappers.ruleDto(saveRule(keyword, categoryId));
    }

    public static void deleteRule(String id) {
        Ctx.require(Ctx.db().get(Entity.CATEGORY_RULE, id), "Regra não encontrada");
        Ctx.db().remove(Entity.CATEGORY_RULE, id);
    }
}
