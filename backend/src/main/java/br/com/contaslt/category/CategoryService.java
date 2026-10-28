package br.com.contaslt.category;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.contaslt.category.CategoryDtos.CategoryDto;
import br.com.contaslt.category.CategoryDtos.CategoryRequest;
import br.com.contaslt.common.BusinessException;
import br.com.contaslt.common.NotFoundException;
import br.com.contaslt.common.TransactionType;
import br.com.contaslt.transaction.TransactionRepository;
import br.com.contaslt.user.User;

@Service
public class CategoryService {

    private record Seed(String name, TransactionType type, String color, String icon) {
    }

    private static final List<Seed> DEFAULTS = List.of(
        new Seed("Moradia", TransactionType.EXPENSE, "#2F6B4F", "house"),
        new Seed("Contas da casa", TransactionType.EXPENSE, "#3D8361", "plug-zap"),
        new Seed("Mercado", TransactionType.EXPENSE, "#C98A2B", "shopping-cart"),
        new Seed("Alimentação", TransactionType.EXPENSE, "#D9622B", "utensils"),
        new Seed("Transporte", TransactionType.EXPENSE, "#3A6EA5", "car"),
        new Seed("Saúde", TransactionType.EXPENSE, "#C2452D", "heart-pulse"),
        new Seed("Educação", TransactionType.EXPENSE, "#6B4FBB", "graduation-cap"),
        new Seed("Assinaturas", TransactionType.EXPENSE, "#8A5CF6", "repeat"),
        new Seed("Lazer", TransactionType.EXPENSE, "#E0A526", "popcorn"),
        new Seed("Vestuário", TransactionType.EXPENSE, "#B5537A", "shirt"),
        new Seed("Impostos e taxas", TransactionType.EXPENSE, "#6B7280", "landmark"),
        new Seed("Cartão de crédito", TransactionType.EXPENSE, "#4B5563", "credit-card"),
        new Seed("Outras despesas", TransactionType.EXPENSE, "#9CA3AF", "circle-ellipsis"),
        new Seed("Salário", TransactionType.INCOME, "#1B5E3F", "briefcase"),
        new Seed("Freelance", TransactionType.INCOME, "#2E8B57", "laptop"),
        new Seed("Investimentos", TransactionType.INCOME, "#0F766E", "trending-up"),
        new Seed("Outras receitas", TransactionType.INCOME, "#6B7280", "circle-plus"));

    private final CategoryRepository categories;
    private final TransactionRepository transactions;

    public CategoryService(CategoryRepository categories, TransactionRepository transactions) {
        this.categories = categories;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> list(Long userId, boolean includeArchived) {
        return categories.findByUserIdOrderByTypeAscNameAsc(userId).stream()
            .filter(c -> includeArchived || !c.isArchived())
            .map(CategoryService::toDto)
            .toList();
    }

    @Transactional
    public CategoryDto create(User user, CategoryRequest req) {
        Category c = new Category();
        c.setUser(user);
        apply(c, req, resolveParent(user.getId(), null, req.parentId()));
        return toDto(categories.save(c));
    }

    @Transactional
    public CategoryDto update(Long userId, Long id, CategoryRequest req) {
        Category c = require(userId, id);
        apply(c, req, resolveParent(userId, c, req.parentId()));
        if (!c.isArchived() && c.getParent() != null && c.getParent().isArchived()) {
            c.getParent().setArchived(false);
        }
        for (Category child : categories.findByParentId(id)) {
            child.setType(c.getType());
            if (c.isArchived()) {
                child.setArchived(true);
            }
        }
        return toDto(c);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Category c = require(userId, id);
        if (categories.existsByParentId(id)) {
            throw new BusinessException("Esta categoria tem subcategorias. Exclua ou mova as subcategorias antes.");
        }
        if (transactions.existsByCategoryId(id)) {
            throw new BusinessException("Esta categoria está em uso. Arquive-a em vez de excluir.");
        }
        categories.delete(c);
    }

    /** A categoria pedida e todas as suas subcategorias: filtrar pelo pai inclui as filhas. */
    @Transactional(readOnly = true)
    public List<Long> idsWithChildren(Long userId, Long id) {
        Category c = require(userId, id);
        List<Long> ids = new ArrayList<>();
        ids.add(c.getId());
        categories.findByParentId(c.getId()).forEach(child -> ids.add(child.getId()));
        return ids;
    }

    private Category resolveParent(Long userId, Category current, Long parentId) {
        if (parentId == null) {
            return null;
        }
        if (current != null && parentId.equals(current.getId())) {
            throw new BusinessException("Uma categoria não pode ser subcategoria dela mesma");
        }
        Category parent = require(userId, parentId);
        if (parent.getParent() != null) {
            throw new BusinessException("\"" + parent.getName() + "\" já é uma subcategoria. Só há um nível de subcategorias.");
        }
        if (current != null && categories.existsByParentId(current.getId())) {
            throw new BusinessException("\"" + current.getName() + "\" tem subcategorias e não pode virar subcategoria.");
        }
        return parent;
    }

    public Category require(Long userId, Long id) {
        return categories.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NotFoundException("Categoria não encontrada"));
    }

    public Category requireOrNull(Long userId, Long id) {
        return id == null ? null : require(userId, id);
    }

    @Transactional
    public void seedDefaults(User user) {
        for (Seed s : DEFAULTS) {
            Category c = new Category();
            c.setUser(user);
            c.setName(s.name());
            c.setType(s.type());
            c.setColor(s.color());
            c.setIcon(s.icon());
            categories.save(c);
        }
    }

    public static CategoryDto toDto(Category c) {
        return new CategoryDto(c.getId(), c.getName(), c.getType(), c.getColor(), c.getIcon(), c.isArchived(),
            c.getParent() == null ? null : c.getParent().getId());
    }

    private static void apply(Category c, CategoryRequest req, Category parent) {
        c.setName(req.name().trim());
        c.setParent(parent);
        // Subcategoria sempre tem o tipo do pai.
        c.setType(parent == null ? req.type() : parent.getType());
        c.setColor(req.color());
        c.setIcon(req.icon() == null || req.icon().isBlank() ? "tag" : req.icon());
        if (req.archived() != null) {
            c.setArchived(req.archived());
        }
    }
}
