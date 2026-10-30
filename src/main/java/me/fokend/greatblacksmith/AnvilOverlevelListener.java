package me.fokend.greatblacksmith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Обработчик овер-лимитных зачарований (книги Прочность IV, Острота VI, Защита V и т.д.)
 * и Кузнечных Ядер (включая Кузнечное Ядро Мастера 25% со страховкой прочности).
 */
public final class AnvilOverlevelListener implements Listener {

    private final FokendGreatBlacksmith plugin;
    private final NamespacedKey coreIdKey;
    private final NamespacedKey specialItemKey;
    private final NamespacedKey customUsesKey;
    private final NamespacedKey overlevelDataKey;
    private final NamespacedKey forgedByMasterKey;
    private final NamespacedKey masterSaveUsedKey;

    private final Map<UUID, CorePlan> corePlans = new HashMap<>();
    private final Map<UUID, BookPlan> bookPlans = new HashMap<>();

    private record CorePlan(ItemStack first, ItemStack second, int targetSlot, int coreSlot,
                            String coreId, double chance, CoreOutcome outcome) {}

    private record CoreOutcome(ItemStack result, boolean success, boolean broken,
                               boolean masterInsured, Enchantment enchantment, int newLevel) {}

    private record BookPlan(ItemStack first, ItemStack second, ItemStack result, int cost) {}

    public AnvilOverlevelListener(FokendGreatBlacksmith plugin) {
        this.plugin = plugin;
        this.coreIdKey = new NamespacedKey(plugin, "upgrade_core_id");
        this.specialItemKey = new NamespacedKey(plugin, "blacksmith_special");
        this.customUsesKey = new NamespacedKey(plugin, "anvil_custom_uses");
        this.overlevelDataKey = new NamespacedKey(plugin, "overlevel_enchants");
        this.forgedByMasterKey = new NamespacedKey(plugin, "forged_by_master");
        this.masterSaveUsedKey = new NamespacedKey(plugin, "master_save_used");
    }

    public void clearAll() {
        corePlans.clear();
        bookPlans.clear();
    }

    // =========================================================================
    // HARD CAPS CONFIGURATION (BOOKS vs CORES)
    // =========================================================================

    public static int getBookCap(Enchantment enchant) {
        if (enchant.equals(Enchantment.SHARPNESS)) return 6;
        if (enchant.equals(Enchantment.PROTECTION)) return 5;
        if (enchant.equals(Enchantment.UNBREAKING)) return 4;
        if (enchant.equals(Enchantment.EFFICIENCY)) return 6;
        if (enchant.equals(Enchantment.POWER)) return 6;
        if (enchant.equals(Enchantment.FEATHER_FALLING)) return 5;
        // Все остальные чары (Mending, Silk Touch, Fire Aspect и т.д.) остаются на ванильном лимите
        return enchant.getMaxLevel();
    }

    public static int getCoreCap(Enchantment enchant) {
        if (enchant.equals(Enchantment.SHARPNESS)) return 7;
        if (enchant.equals(Enchantment.PROTECTION)) return 6;
        if (enchant.equals(Enchantment.UNBREAKING)) return 5;
        if (enchant.equals(Enchantment.EFFICIENCY)) return 7;
        if (enchant.equals(Enchantment.POWER)) return 7;
        if (enchant.equals(Enchantment.FEATHER_FALLING)) return 6;
        // Одноуровневые чары (Mending, Silk Touch, Infinity и т.д.) повышать бессмысленно
        if (enchant.getMaxLevel() <= 1) return enchant.getMaxLevel();
        return enchant.getMaxLevel() + 1;
    }

    private static int getWeightPerLevel(Enchantment enchant) {
        if (enchant.equals(Enchantment.SHARPNESS) || enchant.equals(Enchantment.PROTECTION)) return 4;
        if (enchant.equals(Enchantment.POWER) || enchant.equals(Enchantment.FEATHER_FALLING)) return 3;
        if (enchant.equals(Enchantment.UNBREAKING) || enchant.equals(Enchantment.EFFICIENCY)) return 2;
        return 3;
    }

    // =========================================================================
    // PREPARE ANVIL EVENT (CORES + OVER-LEVELED BOOKS/ITEMS)
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        HumanEntity viewer = event.getView().getPlayer();
        UUID viewerId = viewer.getUniqueId();
        AnvilInventory inventory = event.getInventory();
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();

        String firstCore = getUpgradeCoreId(first);
        String secondCore = getUpgradeCoreId(second);

        // 1. Check if either slot contains an Upgrade Core
        if (firstCore != null || secondCore != null) {
            bookPlans.remove(viewerId);
            handlePrepareCore(event, viewerId, first, second, firstCore, secondCore);
            return;
        }

        corePlans.remove(viewerId);

        // 2. Check if over-leveled enchantments are involved in slot 0 or slot 1
        handlePrepareOverlevelBookOrMerge(event, viewerId, first, second);
    }

    private void handlePrepareCore(PrepareAnvilEvent event, UUID viewerId,
                                   ItemStack first, ItemStack second,
                                   String firstCore, String secondCore) {
        ItemStack target;
        String coreId;
        int targetSlot;
        int coreSlot;

        if (shouldHandleCoreHere(secondCore)) {
            target = first;
            coreId = secondCore;
            targetSlot = 0;
            coreSlot = 1;
        } else if (shouldHandleCoreHere(firstCore)) {
            target = second;
            coreId = firstCore;
            targetSlot = 1;
            coreSlot = 0;
        } else {
            corePlans.remove(viewerId);
            return;
        }

        if (!isValidCoreTarget(target)) {
            corePlans.remove(viewerId);
            event.setResult(null);
            return;
        }

        CorePlan plan = corePlans.get(viewerId);
        if (plan == null || !Objects.equals(plan.first(), first) || !Objects.equals(plan.second(), second)) {
            plan = new CorePlan(first.clone(), second.clone(), targetSlot, coreSlot, coreId, chanceForCore(coreId), null);
            corePlans.put(viewerId, plan);
        }

        ItemStack preview = target.clone();
        preview.setAmount(1);
        event.setResult(preview);
        AnvilView view = event.getView();
        view.setMaximumRepairCost(Integer.MAX_VALUE);
        view.setRepairCost(1);
    }

    private void handlePrepareOverlevelBookOrMerge(PrepareAnvilEvent event, UUID viewerId,
                                                   ItemStack first, ItemStack second) {
        if (first == null || first.getType().isAir() || second == null || second.getType().isAir()) {
            bookPlans.remove(viewerId);
            return;
        }

        boolean secondIsBook = second.getType() == Material.ENCHANTED_BOOK;
        boolean sameMaterial = first.getType() == second.getType();
        if (!secondIsBook && !sameMaterial) {
            bookPlans.remove(viewerId);
            return;
        }

        Map<Enchantment, Integer> firstEnchants = extractEnchants(first);
        Map<Enchantment, Integer> secondEnchants = extractEnchants(second);
        if (secondEnchants.isEmpty() && firstEnchants.isEmpty()) {
            bookPlans.remove(viewerId);
            return;
        }

        boolean hasOverlevelInput = hasAnyOverlevel(firstEnchants) || hasAnyOverlevel(secondEnchants);
        boolean producesOverlevelCombine = false;
        for (Map.Entry<Enchantment, Integer> e : secondEnchants.entrySet()) {
            Enchantment ench = e.getKey();
            int lvl2 = e.getValue();
            int lvl1 = firstEnchants.getOrDefault(ench, 0);
            if (lvl1 == lvl2 && lvl1 >= ench.getMaxLevel() && lvl1 < getBookCap(ench)) {
                producesOverlevelCombine = true;
                break;
            }
        }

        // Only override vanilla anvil when custom/over-leveled enchantments are present or created
        if (!hasOverlevelInput && !producesOverlevelCombine) {
            bookPlans.remove(viewerId);
            return;
        }

        boolean targetIsBook = first.getType() == Material.ENCHANTED_BOOK;
        Map<Enchantment, Integer> merged = new LinkedHashMap<>(firstEnchants);
        boolean anyChanged = false;
        int addedEnchantCost = 0;

        for (Map.Entry<Enchantment, Integer> entry : secondEnchants.entrySet()) {
            Enchantment ench = entry.getKey();
            int lvl2 = entry.getValue();
            int lvl1 = merged.getOrDefault(ench, 0);

            if (!targetIsBook && !canApplyEnchantment(first, ench)) {
                continue;
            }
            if (!targetIsBook && hasConflict(merged, ench)) {
                continue;
            }

            int bookCap = getBookCap(ench);
            // Книга с уровнем выше Hard Cap (например Острота X) не должна обходить лимит
            lvl2 = Math.min(lvl2, Math.max(bookCap, ench.getMaxLevel()));
            int newLevel;
            if (lvl1 == 0) {
                newLevel = lvl2;
            } else if (lvl2 > lvl1) {
                newLevel = lvl2;
            } else if (lvl1 == lvl2 && lvl1 < bookCap) {
                newLevel = lvl1 + 1;
            } else {
                // Preserve existing higher level (e.g. Sharpness VII from Core when adding Unbreaking IV book)
                newLevel = lvl1;
            }

            if (newLevel != lvl1) {
                merged.put(ench, newLevel);
                anyChanged = true;
                int overBonus = Math.max(0, newLevel - ench.getMaxLevel()) * 4;
                addedEnchantCost += newLevel * getWeightPerLevel(ench) + overBonus;
            }
        }

        // Also handle durability repair if combining two identical damageable items
        ItemStack result = first.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            bookPlans.remove(viewerId);
            return;
        }

        if (sameMaterial && !targetIsBook && meta instanceof Damageable dmg1 && second.getItemMeta() instanceof Damageable dmg2) {
            int maxDur = first.getType().getMaxDurability();
            if (maxDur > 0 && dmg1.getDamage() > 0) {
                int rem1 = maxDur - dmg1.getDamage();
                int rem2 = maxDur - dmg2.getDamage();
                int newRem = Math.min(maxDur, rem1 + rem2 + (maxDur * 12 / 100));
                dmg1.setDamage(Math.max(0, maxDur - newRem));
                anyChanged = true;
                addedEnchantCost += 2;
            }
        }

        AnvilView view = event.getView();
        // Имя берём из ванильного результата: клиент всегда присылает renameText (даже ванильное
        // название предмета), и ручное сравнение переименовывало бы каждый предмет.
        ItemStack vanillaResult = event.getResult();
        if (vanillaResult != null && vanillaResult.hasItemMeta()) {
            Component vanillaName = vanillaResult.getItemMeta().displayName();
            if (!Objects.equals(vanillaName, meta.displayName())) {
                meta.displayName(vanillaName);
                anyChanged = true;
                addedEnchantCost += 1;
            }
        }

        if (!anyChanged) {
            bookPlans.remove(viewerId);
            return;
        }

        applyEnchantsAndPdc(meta, merged, targetIsBook);
        int uses1 = getCustomUses(first);
        int uses2 = getCustomUses(second);
        int newUses = Math.max(uses1, uses2) + 1;
        meta.getPersistentDataContainer().set(customUsesKey, PersistentDataType.INTEGER, newUses);
        result.setItemMeta(meta);

        int basePenalty = 2 * (uses1 + uses2);
        int finalCost = Math.clamp(basePenalty + addedEnchantCost, 1, 39);

        view.setMaximumRepairCost(Integer.MAX_VALUE);
        view.setRepairCost(finalCost);
        event.setResult(result);

        bookPlans.put(viewerId, new BookPlan(first.clone(), second.clone(), result.clone(), finalCost));
    }

    // =========================================================================
    // INVENTORY CLICK EVENT (RESULT EXTRACTION FOR CORES & OVER-LEVELED BOOKS)
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilResultClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() != 2
                || !(event.getView().getTopInventory() instanceof AnvilInventory inventory)) {
            return;
        }

        UUID viewerId = player.getUniqueId();
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();

        CorePlan corePlan = corePlans.get(viewerId);
        if (corePlan != null && Objects.equals(corePlan.first(), first) && Objects.equals(corePlan.second(), second)) {
            handleTakeCoreResult(event, player, inventory, corePlan);
            return;
        }

        BookPlan bookPlan = bookPlans.get(viewerId);
        if (bookPlan != null && Objects.equals(bookPlan.first(), first) && Objects.equals(bookPlan.second(), second)) {
            handleTakeBookResult(event, player, inventory, bookPlan);
        }
    }

    private void handleTakeCoreResult(InventoryClickEvent event, Player player, AnvilInventory inventory, CorePlan plan) {
        event.setCancelled(true);
        UUID viewerId = player.getUniqueId();

        int cost = event.getView() instanceof AnvilView anvilView ? Math.max(1, anvilView.getRepairCost()) : 1;
        if (player.getGameMode() != GameMode.CREATIVE && player.getLevel() < cost) {
            player.sendMessage(Component.text("Для обработки ядра нужен как минимум " + cost + " ур. опыта.", NamedTextColor.RED));
            return;
        }

        if (!hasOutputSpace(player, event)) {
            player.sendMessage(Component.text("Освободите место в инвентаре (или на курсоре), чтобы обработать ядро.", NamedTextColor.RED));
            return;
        }

        CoreOutcome outcome = plan.outcome();
        if (outcome == null) {
            outcome = rollCoreOutcome(plan);
            plan = new CorePlan(plan.first(), plan.second(), plan.targetSlot(), plan.coreSlot(), plan.coreId(), plan.chance(), outcome);
            corePlans.put(viewerId, plan);
        }

        if (!outcome.broken()) {
            if (!giveOrCursor(player, event, outcome.result().clone())) {
                return;
            }
        }

        consumeAnvilSlot(inventory, plan.targetSlot());
        consumeAnvilSlot(inventory, plan.coreSlot());
        inventory.setItem(2, null);
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setLevel(player.getLevel() - cost);
        }
        corePlans.remove(viewerId);

        if (outcome.broken()) {
            player.sendMessage(Component.text("✖ Ядро не сработало: предмет разрушен из-за критической потери прочности.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
        } else if (outcome.success()) {
            player.sendMessage(Component.text(
                    "✦ Ядро усилило зачарование " + outcome.enchantment().getKey().getKey() + " до уровня " + outcome.newLevel() + "!",
                    NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0F, 1.2F);
        } else if (outcome.masterInsured()) {
            player.sendMessage(Component.text(
                    "⚠ Ядро Мастера не сработало, но Кузнечная Страховка полностью сохранила предмет (прочность не потеряна). Сейв одноразовый!",
                    NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9F, 0.75F);
        } else {
            player.sendMessage(Component.text("✖ Ядро не сработало: предмет потерял 80% оставшейся прочности.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8F, 0.6F);
        }
        player.updateInventory();
    }

    private void handleTakeBookResult(InventoryClickEvent event, Player player, AnvilInventory inventory, BookPlan plan) {
        event.setCancelled(true);
        UUID viewerId = player.getUniqueId();

        int cost = plan.cost();
        if (player.getGameMode() != GameMode.CREATIVE && player.getLevel() < cost) {
            player.sendMessage(Component.text("Недостаточно опыта! Требуется уровней: " + cost, NamedTextColor.RED));
            return;
        }

        if (!hasOutputSpace(player, event)) {
            player.sendMessage(Component.text("Освободите место в инвентаре, чтобы забрать предмет.", NamedTextColor.RED));
            return;
        }

        if (!giveOrCursor(player, event, plan.result().clone())) {
            return;
        }

        consumeAnvilSlot(inventory, 0);
        consumeAnvilSlot(inventory, 1);
        inventory.setItem(2, null);
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setLevel(player.getLevel() - cost);
        }
        bookPlans.remove(viewerId);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0F, 1.0F);
        player.updateInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory() instanceof AnvilInventory) {
            UUID id = event.getPlayer().getUniqueId();
            corePlans.remove(id);
            bookPlans.remove(id);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        corePlans.remove(id);
        bookPlans.remove(id);
    }

    // =========================================================================
    // ROLL OUTCOME & HELPER METHODS
    // =========================================================================

    private CoreOutcome rollCoreOutcome(CorePlan plan) {
        ItemStack target = plan.targetSlot() == 0 ? plan.first() : plan.second();
        ItemMeta meta = target.getItemMeta();
        Map<Enchantment, Integer> currentEnchants = extractEnchants(target);

        List<Enchantment> eligible = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : currentEnchants.entrySet()) {
            if (entry.getValue() < getCoreCap(entry.getKey())) {
                eligible.add(entry.getKey());
            }
        }
        if (eligible.isEmpty()) {
            return new CoreOutcome(target.clone(), false, false, false, null, 0);
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() < plan.chance()) {
            Enchantment selected = eligible.get(rnd.nextInt(eligible.size()));
            int newLevel = currentEnchants.get(selected) + 1;
            ItemStack upgraded = target.clone();
            upgraded.setAmount(1);
            ItemMeta upgradedMeta = upgraded.getItemMeta();
            currentEnchants.put(selected, newLevel);
            applyEnchantsAndPdc(upgradedMeta, currentEnchants, upgraded.getType() == Material.ENCHANTED_BOOK);
            if ("cb_master".equals(plan.coreId())) {
                upgradedMeta.getPersistentDataContainer().set(forgedByMasterKey, PersistentDataType.BYTE, (byte) 1);
            }
            upgraded.setItemMeta(upgradedMeta);
            return new CoreOutcome(upgraded, true, false, false, selected, newLevel);
        }

        // Кузнечная страховка Ядра Мастера: ПЕРВАЯ неудача на конкретном предмете полностью прощается —
        // прочность не теряется и предмет не ломается. Сейв одноразовый (метка в PDC предмета).
        if ("cb_master".equals(plan.coreId())
                && !meta.getPersistentDataContainer().has(masterSaveUsedKey, PersistentDataType.BYTE)) {
            ItemStack saved = target.clone();
            saved.setAmount(1);
            ItemMeta savedMeta = saved.getItemMeta();
            savedMeta.getPersistentDataContainer().set(masterSaveUsedKey, PersistentDataType.BYTE, (byte) 1);
            saved.setItemMeta(savedMeta);
            return new CoreOutcome(saved, false, false, true, null, 0);
        }

        int maxDurability = target.getType().getMaxDurability();
        int currentDamage = ((Damageable) meta).getDamage();
        int remaining = maxDurability - currentDamage;
        int damageAfter = currentDamage + (int) Math.ceil(remaining * 0.80D);
        boolean broken = remaining <= 5 || damageAfter >= maxDurability;

        ItemStack damaged = target.clone();
        damaged.setAmount(1);
        Damageable damagedMeta = (Damageable) damaged.getItemMeta();
        damagedMeta.setDamage(Math.min(maxDurability, damageAfter));
        damaged.setItemMeta((ItemMeta) damagedMeta);
        return new CoreOutcome(damaged, false, broken, false, null, 0);
    }

    private boolean isValidCoreTarget(ItemStack target) {
        if (target == null || target.getType().isAir()) return false;
        if (target.getType().getMaxDurability() <= 0 || !(target.getItemMeta() instanceof Damageable)) return false;
        Map<Enchantment, Integer> enchants = extractEnchants(target);
        if (enchants.isEmpty()) return false;
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            if (entry.getValue() < getCoreCap(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    public String getUpgradeCoreId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(coreIdKey, PersistentDataType.STRING);
        if (id != null) return id;
        String legId = pdc.get(new NamespacedKey("fokendlegendarywepons", "legendary_id"), PersistentDataType.STRING);
        if ("cb_orange".equals(legId) || "cb_purple".equals(legId) || "cb_green".equals(legId) || "cb_master".equals(legId)) {
            return legId;
        }
        return null;
    }

    private boolean shouldHandleCoreHere(String coreId) {
        if (coreId == null) return false;
        if ("cb_master".equals(coreId)) return true;
        Plugin legPlugin = Bukkit.getPluginManager().getPlugin("FokendLegendaryWepons");
        return legPlugin == null || !legPlugin.isEnabled();
    }

    private double chanceForCore(String coreId) {
        return switch (coreId) {
            case "cb_orange" -> 0.10D;
            case "cb_purple" -> 0.15D;
            case "cb_green" -> 0.17D;
            case "cb_master" -> 0.25D;
            default -> 0.0D;
        };
    }

    private Map<Enchantment, Integer> extractEnchants(ItemStack item) {
        Map<Enchantment, Integer> map = new LinkedHashMap<>();
        if (item == null || !item.hasItemMeta()) return map;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta esm) {
            map.putAll(esm.getStoredEnchants());
        }
        map.putAll(meta.getEnchants());
        return map;
    }

    private void applyEnchantsAndPdc(ItemMeta meta, Map<Enchantment, Integer> enchants, boolean asStoredBook) {
        if (asStoredBook && meta instanceof EnchantmentStorageMeta esm) {
            for (Enchantment existing : new ArrayList<>(esm.getStoredEnchants().keySet())) {
                esm.removeStoredEnchant(existing);
            }
            for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                esm.addStoredEnchant(e.getKey(), e.getValue(), true);
            }
        } else {
            for (Enchantment existing : new ArrayList<>(meta.getEnchants().keySet())) {
                meta.removeEnchant(existing);
            }
            for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                meta.addEnchant(e.getKey(), e.getValue(), true);
            }
        }

        // Mirror over-leveled enchantments into PDC TAG_CONTAINER for persistence/audit
        PersistentDataContainer rootPdc = meta.getPersistentDataContainer();
        PersistentDataContainer overPdc = rootPdc.getAdapterContext().newPersistentDataContainer();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            if (e.getValue() > e.getKey().getMaxLevel()) {
                overPdc.set(new NamespacedKey(plugin, e.getKey().getKey().getKey()), PersistentDataType.INTEGER, e.getValue());
            }
        }
        rootPdc.set(overlevelDataKey, PersistentDataType.TAG_CONTAINER, overPdc);
    }

    private boolean hasAnyOverlevel(Map<Enchantment, Integer> enchants) {
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            if (e.getValue() > e.getKey().getMaxLevel()) return true;
        }
        return false;
    }

    private boolean canApplyEnchantment(ItemStack target, Enchantment enchant) {
        if (enchant.canEnchantItem(target)) return true;
        Material type = target.getType();
        if (enchant.equals(Enchantment.SHARPNESS) && type.name().endsWith("_AXE")) {
            return true;
        }
        if (type == Material.STICK && target.hasItemMeta()) {
            String special = target.getItemMeta().getPersistentDataContainer().get(specialItemKey, PersistentDataType.STRING);
            if ("mace_stick".equals(special)) {
                return enchant.equals(Enchantment.SHARPNESS)
                        || enchant.equals(Enchantment.KNOCKBACK)
                        || enchant.equals(Enchantment.FIRE_ASPECT)
                        || enchant.equals(Enchantment.SMITE);
            }
        }
        return false;
    }

    private boolean hasConflict(Map<Enchantment, Integer> current, Enchantment candidate) {
        for (Enchantment existing : current.keySet()) {
            if (!existing.equals(candidate) && existing.conflictsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    private int getCustomUses(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer uses = item.getItemMeta().getPersistentDataContainer().get(customUsesKey, PersistentDataType.INTEGER);
        return uses != null ? uses : 0;
    }

    private boolean hasOutputSpace(Player player, InventoryClickEvent event) {
        if (event.isShiftClick()) return player.getInventory().firstEmpty() != -1;
        ItemStack cursor = event.getCursor();
        return cursor == null || cursor.getType().isAir();
    }

    private boolean giveOrCursor(Player player, InventoryClickEvent event, ItemStack output) {
        if (event.isShiftClick()) {
            return player.getInventory().addItem(output).isEmpty();
        }
        event.getView().setCursor(output);
        return true;
    }

    private void consumeAnvilSlot(AnvilInventory inv, int slot) {
        ItemStack item = inv.getItem(slot);
        if (item == null || item.getType().isAir()) return;
        if (item.getAmount() <= 1) inv.setItem(slot, null);
        else item.setAmount(item.getAmount() - 1);
    }
}
