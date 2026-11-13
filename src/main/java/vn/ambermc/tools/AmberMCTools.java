package vn.ambermc.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AmberMCTools extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey toolTypeKey;
    private NamespacedKey expiresAtKey;
    private NamespacedKey pendingSecondsKey;

    private final Set<UUID> breakingGuard = new HashSet<>();
    private final Map<UUID, Long> lastMiningActivity = new HashMap<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private final Pattern durationPattern = Pattern.compile("(\\d+)([wdhms])");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        toolTypeKey = new NamespacedKey(this, "tool_type");
        expiresAtKey = new NamespacedKey(this, "expires_at");
        pendingSecondsKey = new NamespacedKey(this, "pending_seconds");

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("ambermctools");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                boolean isActivelyMining = now - lastMiningActivity.getOrDefault(player.getUniqueId(), 0L) < 2000L;
                refreshPlayerInventory(player, isActivelyMining);
            }
        }, 100L, 100L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        lastMiningActivity.put(player.getUniqueId(), System.currentTimeMillis());

        ItemStack tool = player.getInventory().getItemInMainHand();
        String type = getToolType(tool);
        if (type == null) return;

        refreshToolState(player, tool);
        if (isExpiredAndRemove(player, tool)) {
            event.setCancelled(true);
            return;
        }

        if (getConfig().getBoolean("auto-pickup", true) && event.getExpToDrop() > 0) {
            player.giveExp(event.getExpToDrop());
            event.setExpToDrop(0);
        }

        if (breakingGuard.contains(player.getUniqueId())) return;

        if (getConfig().getBoolean("sneak-single-block", true) && player.isSneaking()) {
            return;
        }

        Block origin = event.getBlock();
        breakingGuard.add(player.getUniqueId());
        try {
            if ("PICKAXE".equals(type) || "SHOVEL".equals(type)) {
                handleAreaBreak(player, origin, type);
            } else if ("AXE".equals(type)) {
                handleTreeBreak(player, origin);
            }
        } finally {
            breakingGuard.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (!getConfig().getBoolean("auto-pickup", true)) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (getToolType(tool) == null) return;

        Iterator<Item> iterator = event.getItems().iterator();
        while (iterator.hasNext()) {
            Item drop = iterator.next();
            ItemStack stack = drop.getItemStack();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (leftover.isEmpty()) {
                iterator.remove();
            } else {
                drop.setItemStack(leftover.values().iterator().next());
                drop.teleport(player.getLocation());
            }
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getNewSlot());
        if (getToolType(item) == null) return;

        refreshToolState(player, item);
        if (!player.isOp() && isToolExpired(item)) {
            player.getInventory().setItem(event.getNewSlot(), null);
            notifyExpired(player);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (getToolType(item) == null) return;

        refreshToolState(player, item);
        if (isExpiredAndRemove(player, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (getToolType(event.getCurrentItem()) != null || getToolType(event.getCursor()) != null) {
            Bukkit.getScheduler().runTask(this, () -> refreshPlayerInventory(player, false));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refreshPlayerInventory(player, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && getToolType(event.getItem().getItemStack()) != null) {
            refreshToolState(player, event.getItem().getItemStack());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshPlayerInventory(event.getPlayer(), false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastMiningActivity.remove(event.getPlayer().getUniqueId());
    }

    private void refreshPlayerInventory(Player player, boolean skipMainHand) {
        PlayerInventory inv = player.getInventory();
        int heldSlot = inv.getHeldItemSlot();

        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (skipMainHand && slot == heldSlot) continue;
            ItemStack item = inv.getItem(slot);
            if (getToolType(item) == null) continue;

            refreshToolState(player, item);
            if (!player.isOp() && isToolExpired(item)) {
                inv.setItem(slot, null);
                notifyExpired(player);
            }
        }
    }

    private void refreshToolState(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String type = pdc.get(toolTypeKey, PersistentDataType.STRING);
        if (type == null) return;

        Long pendingSeconds = pdc.get(pendingSecondsKey, PersistentDataType.LONG);
        if (pendingSeconds != null && pendingSeconds > 0L) {
            if (player.isOp()) {
                return;
            }
            long expiresAt = System.currentTimeMillis() + (pendingSeconds * 1000L);
            pdc.remove(pendingSecondsKey);
            pdc.set(expiresAtKey, PersistentDataType.LONG, expiresAt);
            updateLore(meta, type, expiresAt - System.currentTimeMillis(), false);
            item.setItemMeta(meta);
            return;
        }

        Long expiresAt = pdc.get(expiresAtKey, PersistentDataType.LONG);
        if (expiresAt != null && expiresAt > 0L) {
            long remaining = expiresAt - System.currentTimeMillis();
            if (remaining > 0L && updateLore(meta, type, remaining, false)) {
                item.setItemMeta(meta);
            }
        }
    }

    private boolean updateLore(ItemMeta meta, String type, long remainingMillis, boolean isPending) {
        String formatted = formatCompactDuration(remainingMillis);
        String template = isPending
                ? getConfig().getString("lore.pending", "&eThời hạn: &f%duration%").replace("%duration%", formatted)
                : getConfig().getString("lore.expires-at", "&eHết hạn: &f%time%").replace("%time%", formatted);

        Component skillLine = color(getSkillDescription(type));
        Component timeLine = color(template);

        List<Component> currentLore = meta.lore();
        if (currentLore != null && currentLore.size() == 2
                && currentLore.get(0).equals(skillLine)
                && currentLore.get(1).equals(timeLine)) {
            return false;
        }

        meta.lore(List.of(skillLine, timeLine));
        return true;
    }

    private boolean isExpiredAndRemove(Player player, ItemStack item) {
        if (player.isOp() || !isToolExpired(item)) return false;
        if (item.equals(player.getInventory().getItemInMainHand())) {
            player.getInventory().setItemInMainHand(null);
        } else if (item.equals(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
        notifyExpired(player);
        return true;
    }

    private void notifyExpired(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        String msg = getConfig().getString("messages.expired", "&cCông cụ của bạn đã hết hạn!");
        player.sendActionBar(color(msg));
    }

    private boolean isToolExpired(ItemStack item) {
        long expiresAt = getExpiresAt(item);
        return expiresAt > 0L && System.currentTimeMillis() >= expiresAt;
    }

    private void handleAreaBreak(Player player, Block origin, String type) {
        BlockFace face = BlockFace.UP;
        RayTraceResult ray = player.rayTraceBlocks(6.0);
        if (ray != null && ray.getHitBlockFace() != null) {
            face = ray.getHitBlockFace();
        }

        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                if (player.getInventory().getItemInMainHand().getType().isAir()) return;

                Block target = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    case EAST, WEST -> origin.getRelative(0, b, a);
                    default -> origin.getRelative(a, 0, b);
                };

                if (isValidAreaBlock(target, type)) {
                    player.breakBlock(target);
                }
            }
        }
    }

    private boolean isValidAreaBlock(Block block, String type) {
        Material mat = block.getType();
        if (mat.isAir() || mat.getHardness() < 0) return false;
        if (mat == Material.SPAWNER || mat == Material.END_PORTAL_FRAME || mat == Material.BARRIER) return false;

        if ("PICKAXE".equals(type)) {
            return Tag.MINEABLE_PICKAXE.isTagged(mat);
        }
        if ("SHOVEL".equals(type)) {
            return Tag.MINEABLE_SHOVEL.isTagged(mat);
        }
        return false;
    }

    private void handleTreeBreak(Player player, Block origin) {
        if (!Tag.LOGS.isTagged(origin.getType())) return;

        int maxBlocks = Math.max(1, getConfig().getInt("max-tree-blocks", 500));
        Queue<Block> queue = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();

        queue.add(origin);
        visited.add(origin);
        int broken = 1;

        while (!queue.isEmpty() && broken < maxBlocks) {
            if (player.getInventory().getItemInMainHand().getType().isAir()) return;
            Block current = queue.poll();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (broken >= maxBlocks) break;

                        Block neighbor = current.getRelative(dx, dy, dz);
                        if (visited.contains(neighbor)) continue;
                        visited.add(neighbor);

                        if (Tag.LOGS.isTagged(neighbor.getType())) {
                            queue.add(neighbor);
                            player.breakBlock(neighbor);
                            broken++;
                        }
                    }
                }
            }
        }
    }

    private ItemStack createTool(String type, int amount, long durationSeconds, boolean pendingForOp) {
        Material material = switch (type) {
            case "PICKAXE" -> Material.NETHERITE_PICKAXE;
            case "SHOVEL" -> Material.NETHERITE_SHOVEL;
            case "AXE" -> Material.NETHERITE_AXE;
            default -> Material.NETHERITE_PICKAXE;
        };

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(color(getConfiguredName(type)));
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(toolTypeKey, PersistentDataType.STRING, type);

        if (durationSeconds > 0L) {
            if (pendingForOp) {
                pdc.set(pendingSecondsKey, PersistentDataType.LONG, durationSeconds);
                updateLore(meta, type, durationSeconds * 1000L, true);
            } else {
                long expiresAt = System.currentTimeMillis() + (durationSeconds * 1000L);
                pdc.set(expiresAtKey, PersistentDataType.LONG, expiresAt);
                updateLore(meta, type, durationSeconds * 1000L, false);
            }
        } else {
            String permLine = getConfig().getString("lore.permanent", "&eThời hạn: &aVĩnh viễn");
            meta.lore(List.of(
                    color(getSkillDescription(type)),
                    color(permLine)
            ));
        }

        item.setItemMeta(meta);
        return item;
    }

    private long parseDurationSeconds(String input) {
        String s = input.trim().toLowerCase();
        if (s.equals("vv") || s.equals("perm") || s.equals("permanent") || s.equals("vinhvien")) {
            return 0L;
        }
        Matcher matcher = durationPattern.matcher(s);
        long total = 0L;
        int matchedLength = 0;
        while (matcher.find()) {
            long val = Long.parseLong(matcher.group(1));
            switch (matcher.group(2)) {
                case "w" -> total += val * 604800L;
                case "d" -> total += val * 86400L;
                case "h" -> total += val * 3600L;
                case "m" -> total += val * 60L;
                case "s" -> total += val;
            }
            matchedLength += matcher.group(0).length();
        }
        if (matchedLength == 0 || matchedLength != s.length()) {
            return -1L;
        }
        return total;
    }

    private String formatCompactDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        if (totalSeconds < 60L) {
            return "< 1m";
        }
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        return sb.toString().trim();
    }

    private String getConfiguredName(String type) {
        return switch (type) {
            case "PICKAXE" -> getConfig().getString("pickaxe-name", "&dAMETHYST PICKAXE");
            case "SHOVEL" -> getConfig().getString("shovel-name", "&dAMETHYST SHOVEL");
            case "AXE" -> getConfig().getString("axe-name", "&dAMETHYST AXE");
            default -> "&dAMETHYST TOOL";
        };
    }

    private String getSkillDescription(String type) {
        return switch (type) {
            case "PICKAXE" -> getConfig().getString("lore.pickaxe-skill", "&fĐào 9 block cùng lúc");
            case "SHOVEL" -> getConfig().getString("lore.shovel-skill", "&fĐào 9 block đất/cát cùng lúc");
            case "AXE" -> getConfig().getString("lore.axe-skill", "&fChặt cây tức thì");
            default -> "";
        };
    }

    private String getToolType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(toolTypeKey, PersistentDataType.STRING);
    }

    private long getExpiresAt(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0L;
        Long val = item.getItemMeta().getPersistentDataContainer().get(expiresAtKey, PersistentDataType.LONG);
        return val != null ? val : 0L;
    }

    private Component color(String text) {
        return Component.text()
                .decoration(TextDecoration.ITALIC, false)
                .append(legacy.deserialize(text))
                .build();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(color(getConfig().getString("messages.reloaded", "&#00d2ffĐã tải lại cấu hình AmberMCTools!")));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (args.length < 3) {
                sender.sendMessage(color(getConfig().getString("messages.give-usage", "&cSử dụng: /at give <người_chơi> <amethystpickaxe|amethystshovel|amethystaxe> [thời_gian] [số_lượng]")));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color(getConfig().getString("messages.player-offline", "&cNgười chơi này hiện không trực tuyến.")));
                return true;
            }

            String type = args[2].toUpperCase()
                    .replace("AMETHYST_", "")
                    .replace("AMETHYST", "");
            if (!type.equals("PICKAXE") && !type.equals("SHOVEL") && !type.equals("AXE")) {
                sender.sendMessage(color(getConfig().getString("messages.invalid-tool", "&cLoại công cụ không hợp lệ! Chọn: amethystpickaxe, amethystshovel, amethystaxe")));
                return true;
            }

            int amount = Math.max(1, Math.min(64, getConfig().getInt("default-amount", 1)));
            long defaultSeconds = Math.max(1L, getConfig().getLong("tool-expiry-seconds", 259200L));
            long durationSeconds = getConfig().getBoolean("tool-expiry-enabled", true) ? defaultSeconds : 0L;

            for (int i = 3; i < Math.min(args.length, 5); i++) {
                String token = args[i];
                if (token.matches("\\d+")) {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(token)));
                } else {
                    long parsed = parseDurationSeconds(token);
                    if (parsed < 0L) {
                        sender.sendMessage(color(getConfig().getString("messages.invalid-duration", "&cThời gian không hợp lệ! Ví dụ: vv, 3d, 2d, 1d, 12h, 30m")));
                        return true;
                    }
                    durationSeconds = parsed;
                }
            }

            target.getInventory().addItem(createTool(type, amount, durationSeconds, target.isOp()));

            String toolName = getConfiguredName(type);
            String givenMsg = getConfig().getString("messages.given", "&#00d2ffĐã trao &f%amount%x %tool% &#00d2ffcho &f%player%")
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%tool%", toolName)
                    .replace("%player%", target.getName());
            sender.sendMessage(color(givenMsg));

            if (!sender.equals(target)) {
                String receivedMsg = getConfig().getString("messages.received", "&#00d2ffBạn đã nhận được &f%amount%x %tool%")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%tool%", toolName);
                target.sendMessage(color(receivedMsg));
            }
            return true;
        }

        sender.sendMessage(color(getConfig().getString("messages.usage", "&cSử dụng: /at <give|reload>")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String lastArg = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        if (args.length == 1) {
            return filterPrefix(List.of("give", "reload"), lastArg);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filterPrefix(names, lastArg);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filterPrefix(List.of("amethystpickaxe", "amethystshovel", "amethystaxe"), lastArg);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return filterPrefix(List.of("vv", "3d", "2d", "1d"), lastArg);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("give")) {
            if (args[3].matches("\\d+")) {
                return filterPrefix(List.of("vv", "3d", "2d", "1d"), lastArg);
            }
            return filterPrefix(List.of(String.valueOf(Math.max(1, getConfig().getInt("default-amount", 1)))), lastArg);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        if (prefix.isEmpty()) return options;
        List<String> result = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(prefix)) {
                result.add(opt);
            }
        }
        return result;
    }
}
