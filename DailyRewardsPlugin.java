package com.lightsmp.dailyrewards;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class DailyRewardsPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> rolling = new HashSet<>();
    private NamespacedKey actionKey;
    private static final long HOUR = 3_600_000L;

    @Override public void onEnable() {
        saveDefaultConfig();
        actionKey = new NamespacedKey(this, "daily_action");
        loadCooldowns();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("daily")).setExecutor(this::command);
        getLogger().info("DailyRewards enabled.");
    }

    @Override public void onDisable() { saveCooldowns(); }

    private boolean command(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("dailyrewards.admin")) return deny(sender);
            reloadConfig(); sender.sendMessage(color("&aDailyRewards configuration reloaded.")); return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("dailyrewards.admin")) return deny(sender);
            if (args.length < 2) { sender.sendMessage(color("&cUsage: /daily reset <player>")); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage(color("&cThat player must be online.")); return true; }
            cooldowns.remove(target.getUniqueId()); saveCooldowns();
            sender.sendMessage(color("&aReset daily cooldown for " + target.getName() + ".")); return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        if (!player.hasPermission("dailyrewards.use")) return deny(player);
        if (args.length > 0 && args[0].equalsIgnoreCase("editor")) {
            if (!player.hasPermission("dailyrewards.admin")) return deny(player);
            openEditor(player); return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("claim")) { beginClaim(player); return true; }
        openMenu(player); return true;
    }

    private boolean deny(CommandSender sender) { sender.sendMessage(color("&cYou don't have permission.")); return true; }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private long cooldownMillis() { return Math.max(1, getConfig().getLong("settings.cooldown-hours", 24)) * HOUR; }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color(getConfig().getString("settings.menu-title", "&8Daily Rewards")));
        fill(inv);
        long remaining = cooldowns.getOrDefault(p.getUniqueId(), 0L) + cooldownMillis() - System.currentTimeMillis();
        ItemStack claim = named(Material.CHEST, remaining > 0 ? "&cReward on cooldown" : "&aClaim daily reward",
                remaining > 0 ? List.of("&7Available in: &f" + format(remaining)) : List.of("&7Click to reveal a random reward!"));
        setAction(claim, "claim"); inv.setItem(13, claim);
        inv.setItem(22, named(Material.CLOCK, "&eDaily cooldown", List.of("&7One claim every 24 hours.", "&7Your cooldown persists through restarts.")));
        p.openInventory(inv);
    }

    private void openEditor(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, color(getConfig().getString("settings.editor-title", "&8Daily Rewards Editor")));
        fill(inv);
        List<String> ids = rewardIds();
        int slot = 10;
        for (String id : ids) {
            if (slot == 17) slot = 19;
            if (slot >= 44) break;
            String path = "rewards." + id;
            Material mat = material(getConfig().getString(path + ".material", "STONE"));
            double chance = getConfig().getDouble(path + ".chance", 0);
            int amount = getConfig().getInt(path + ".amount", 1);
            boolean enabled = getConfig().getBoolean(path + ".enabled", true);
            ItemStack item = new ItemStack(mat, Math.max(1, Math.min(amount, mat.getMaxStackSize())));
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color((enabled ? "&a" : "&c") + id));
            meta.setLore(List.of(color("&7Amount: &f" + amount), color("&7Weight: &f" + chance), color("&7Enabled: " + (enabled ? "&aYes" : "&cNo")), color("&eLeft-click: toggle"), color("&eRight-click: cycle chance")));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "edit:" + id);
            item.setItemMeta(meta); inv.setItem(slot++, item);
        }
        inv.setItem(49, named(Material.EMERALD, "&aSave configuration", List.of("&7Changes are saved automatically.")));
        p.openInventory(inv);
    }

    private void beginClaim(Player p) {
        UUID uuid = p.getUniqueId();
        if (rolling.contains(uuid)) return;
        long remaining = cooldowns.getOrDefault(uuid, 0L) + cooldownMillis() - System.currentTimeMillis();
        if (remaining > 0) { p.sendMessage(color("&cYou can claim again in &f" + format(remaining) + "&c.")); return; }
        List<String> ids = rewardIds().stream().filter(id -> getConfig().getBoolean("rewards." + id + ".enabled", true)
                && getConfig().getDouble("rewards." + id + ".chance", 0) > 0 && material(getConfig().getString("rewards." + id + ".material", "STONE")) != Material.AIR).toList();
        if (ids.isEmpty()) { p.sendMessage(color("&cNo valid rewards are configured.")); return; }
        String winner = weightedPick(ids);
        rolling.add(uuid);
        p.closeInventory();
        Inventory animation = Bukkit.createInventory(null, 27, color("&8Revealing reward..."));
        p.openInventory(animation);
        int duration = Math.max(20, getConfig().getInt("settings.animation-ticks", 60));
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!p.isOnline()) { rolling.remove(uuid); cancel(); return; }
                if (p.getOpenInventory().getTopInventory() != animation) { rolling.remove(uuid); cancel(); return; }
                String shown = tick >= duration - 5 ? winner : ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
                ItemStack icon = createReward(shown);
                animation.setItem(13, icon);
                animation.setItem(4, named(Material.CLOCK, "&eRolling...", List.of("&7Please wait...")));
                tick++;
                if (tick >= duration) {
                    cancel(); rolling.remove(uuid);
                    // Recheck at award time; only this claim path can set the timestamp.
                    long left = cooldowns.getOrDefault(uuid, 0L) + cooldownMillis() - System.currentTimeMillis();
                    if (left > 0) { p.closeInventory(); p.sendMessage(color("&cYour reward is still on cooldown.")); return; }
                    cooldowns.put(uuid, System.currentTimeMillis()); saveCooldowns();
                    ItemStack reward = createReward(winner);
                    deliver(p, reward);
                    p.closeInventory();
                    p.sendTitle(color("&6&lREWARD!"), color("&f" + displayName(reward)), 5, 45, 10);
                    p.sendMessage(color("&aYou received &f" + displayName(reward) + "&a!"));
                    double chance = getConfig().getDouble("rewards." + winner + ".chance", 0);
                    if (getConfig().getBoolean("settings.announce-rare-rewards", true) && chance <= getConfig().getDouble("settings.rare-announcement-threshold", 5.0))
                        Bukkit.broadcastMessage(color("&6[Daily] &e" + p.getName() + " &7won a rare daily reward: &f" + displayName(reward)));
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void deliver(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(item);
        if (leftovers.isEmpty()) return;
        // Safe default: never delete an earned reward when inventory space is unavailable.
        leftovers.values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(color("&eYour inventory was full, so the reward was dropped safely at your location."));
    }

    private String weightedPick(List<String> ids) {
        double total = ids.stream().mapToDouble(id -> getConfig().getDouble("rewards." + id + ".chance", 0)).sum();
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (String id : ids) { roll -= getConfig().getDouble("rewards." + id + ".chance", 0); if (roll < 0) return id; }
        return ids.get(ids.size() - 1);
    }

    private ItemStack createReward(String id) {
        String path = "rewards." + id;
        Material mat = material(getConfig().getString(path + ".material", "STONE"));
        int amount = Math.max(1, getConfig().getInt(path + ".amount", 1));
        ItemStack item = new ItemStack(mat, Math.min(amount, 64));
        if (getConfig().getBoolean(path + ".random-enchantment", false) && mat == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            List<Enchantment> enchants = RegistryEnchantments.all();
            if (!enchants.isEmpty()) { Enchantment e = enchants.get(ThreadLocalRandom.current().nextInt(enchants.size())); meta.addStoredEnchant(e, Math.max(1, e.getMaxLevel()), true); }
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> rewardIds() { return new ArrayList<>(Objects.requireNonNull(getConfig().getConfigurationSection("rewards")).getKeys(false)); }
    private Material material(String name) { try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Material.AIR; } }
    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(color(name)); meta.setLore(lore.stream().map(this::color).toList()); item.setItemMeta(meta); return item;
    }
    private void setAction(ItemStack item, String action) { ItemMeta meta = item.getItemMeta(); meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); item.setItemMeta(meta); }
    private void fill(Inventory inv) { ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()); for (int i=0;i<inv.getSize();i++) inv.setItem(i,pane); }
    private String displayName(ItemStack item) { return item.getType().name().toLowerCase(Locale.ROOT).replace('_',' ' ) + " x" + item.getAmount(); }
    private String format(long millis) { long sec = Math.max(0, millis / 1000); return String.format("%02dh %02dm %02ds", sec/3600, (sec%3600)/60, sec%60); }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!title.equals(color(getConfig().getString("settings.menu-title", "&8Daily Rewards"))) && !title.equals(color(getConfig().getString("settings.editor-title", "&8Daily Rewards Editor"))) && !title.equals(color("&8Revealing reward..."))) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack clicked = e.getCurrentItem(); if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("claim")) beginClaim(p);
        else if (action.startsWith("edit:") && p.hasPermission("dailyrewards.admin")) {
            String id = action.substring(5), path = "rewards." + id;
            if (e.isLeftClick()) getConfig().set(path + ".enabled", !getConfig().getBoolean(path + ".enabled", true));
            else { double next = getConfig().getDouble(path + ".chance", 1) + 1; if (next > 50) next = 1; getConfig().set(path + ".chance", next); }
            saveConfig(); openEditor(p);
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(color(getConfig().getString("settings.menu-title", "&8Daily Rewards"))) || title.equals(color(getConfig().getString("settings.editor-title", "&8Daily Rewards Editor"))) || title.equals(color("&8Revealing reward..."))) e.setCancelled(true);
    }
    private void loadCooldowns() { var section = getConfig().getConfigurationSection("cooldowns"); if (section != null) for (String key : section.getKeys(false)) try { cooldowns.put(UUID.fromString(key), section.getLong(key)); } catch (IllegalArgumentException ignored) {} }
    private void saveCooldowns() { for (var entry : cooldowns.entrySet()) getConfig().set("cooldowns." + entry.getKey(), entry.getValue()); saveConfig(); }

    private static final class RegistryEnchantments {
        static List<Enchantment> all() {
            List<Enchantment> out = new ArrayList<>();
            for (Enchantment e : org.bukkit.Registry.ENCHANTMENT) if (e != null && e.isDiscoverable()) out.add(e);
            return out;
        }
    }
}
