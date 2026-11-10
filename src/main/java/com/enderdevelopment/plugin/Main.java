package com.enderdevelopment.plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class Main extends JavaPlugin implements Listener, CommandExecutor {
    private static final String MENU_TITLE = "Lantern Brightness";
    private final Map<String, LanternEntry> lanterns = new HashMap<>();
    private final Map<UUID, PendingAction> pendingActions = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "lanterns.yml");
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create the plugin data folder.");
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadLanterns();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("lantern") != null) getCommand("lantern").setExecutor(this);
        Bukkit.getScheduler().runTaskLater(this, this::restoreAllLightBlocks, 1L);
        getLogger().info("Loaded " + lanterns.size() + " configured lantern(s).");
    }

    @Override
    public void onDisable() {
        saveLanterns();
        for (LanternEntry entry : lanterns.values()) removeLightBlock(entry);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can configure lanterns.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            openBrightnessMenu(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            int level;
            try {
                level = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Brightness must be a number from 1 to 15.", NamedTextColor.RED));
                return true;
            }
            if (level < 1 || level > 15) {
                player.sendMessage(Component.text("Brightness must be between 1 and 15.", NamedTextColor.RED));
                return true;
            }
            armSelection(player, level);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            armSelection(player, 0);
            return true;
        }
        player.sendMessage(Component.text("Usage: /lantern [menu] | /lantern set <1-15> | /lantern clear", NamedTextColor.RED));
        return true;
    }

    private void openBrightnessMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(MENU_TITLE, NamedTextColor.GOLD));
        Material[] icons = {Material.BLACK_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE,
                Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE,
                Material.LIME_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
                Material.ORANGE_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE,
                Material.GLOWSTONE, Material.LANTERN, Material.SOUL_LANTERN,
                Material.END_ROD, Material.SEA_LANTERN, Material.SHROOMLIGHT,
                Material.JACK_O_LANTERN};
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(Component.text(" "));
            filler.setItemMeta(fillerMeta);
        }
        for (int slot = 15; slot < 27; slot++) inventory.setItem(slot, filler);
        for (int level = 1; level <= 15; level++) {
            ItemStack item = new ItemStack(icons[level - 1]);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("Brightness " + level, NamedTextColor.YELLOW));
                meta.lore(java.util.List.of(Component.text("Click, then right-click a lantern", NamedTextColor.GRAY)));
                item.setItemMeta(meta);
            }
            inventory.setItem(level - 1, item);
        }
        ItemStack clear = new ItemStack(Material.BARRIER);
        ItemMeta meta = clear.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Clear custom brightness", NamedTextColor.RED));
            meta.lore(java.util.List.of(Component.text("Click, then right-click a configured lantern", NamedTextColor.GRAY)));
            clear.setItemMeta(meta);
        }
        inventory.setItem(22, clear);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(Component.text(MENU_TITLE, NamedTextColor.GOLD))) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 15) {
            armSelection(player, slot + 1);
            player.closeInventory();
        } else if (slot == 22) {
            armSelection(player, 0);
            player.closeInventory();
        }
    }

    private void armSelection(Player player, int level) {
        pendingActions.put(player.getUniqueId(), new PendingAction(level));
        if (level == 0) {
            player.sendMessage(Component.text("Right-click a configured lantern to remove its custom brightness.", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Right-click a lantern to assign brightness level " + level + ".", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onLanternInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        PendingAction action = pendingActions.get(event.getPlayer().getUniqueId());
        if (action == null || !isLantern(event.getClickedBlock().getType())) return;
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        String key = locationKey(block.getLocation());
        LanternEntry oldEntry = lanterns.remove(key);
        if (oldEntry != null) removeLightBlock(oldEntry);
        if (action.level == 0) {
            saveLanterns();
            event.getPlayer().sendMessage(Component.text("Custom brightness removed.", NamedTextColor.GREEN));
        } else {
            LanternEntry entry = new LanternEntry(block.getLocation(), action.level);
            lanterns.put(key, entry);
            placeLightBlock(entry);
            saveLanterns();
            event.getPlayer().sendMessage(Component.text("Lantern brightness set to level " + action.level + ".", NamedTextColor.GREEN));
        }
        pendingActions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onLanternBreak(BlockBreakEvent event) {
        LanternEntry entry = lanterns.remove(locationKey(event.getBlock().getLocation()));
        if (entry != null) {
            removeLightBlock(entry);
            saveLanterns();
        }
    }

    private void loadLanterns() {
        ConfigurationSection section = data.getConfigurationSection("lanterns");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String worldName = section.getString(key + ".world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) continue;
            Location location = new Location(world, section.getInt(key + ".x"), section.getInt(key + ".y"), section.getInt(key + ".z"));
            int level = section.getInt(key + ".level");
            if (level >= 1 && level <= 15) lanterns.put(key, new LanternEntry(location, level));
        }
    }

    private void saveLanterns() {
        data = new YamlConfiguration();
        int index = 0;
        for (LanternEntry entry : lanterns.values()) {
            String path = "lanterns." + index++;
            Location location = entry.location;
            data.set(path + ".world", location.getWorld().getName());
            data.set(path + ".x", location.getBlockX());
            data.set(path + ".y", location.getBlockY());
            data.set(path + ".z", location.getBlockZ());
            data.set(path + ".level", entry.level);
        }
        try {
            data.save(dataFile);
        } catch (Exception exception) {
            getLogger().warning("Could not save lantern data: " + exception.getMessage());
        }
    }

    private void restoreAllLightBlocks() {
        for (LanternEntry entry : lanterns.values()) {
            if (isLantern(entry.location.getBlock().getType())) placeLightBlock(entry);
        }
    }

    private void placeLightBlock(LanternEntry entry) {
        Block block = entry.location.clone().add(0, 1, 0).getBlock();
        if (!block.getType().isAir() && block.getType() != Material.LIGHT) return;
        block.setType(Material.LIGHT, false);
        BlockData blockData = block.getBlockData();
        if (blockData instanceof Light light) {
            light.setLevel(entry.level);
            light.setWaterlogged(false);
            block.setBlockData(light, false);
        }
    }

    private void removeLightBlock(LanternEntry entry) {
        Block block = entry.location.clone().add(0, 1, 0).getBlock();
        if (block.getType() == Material.LIGHT) block.setType(Material.AIR, false);
    }

    private boolean isLantern(Material material) {
        return material == Material.LANTERN || material == Material.SOUL_LANTERN;
    }

    private String locationKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private record LanternEntry(Location location, int level) {}
    private record PendingAction(int level) {}
}
