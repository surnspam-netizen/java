package com.example.chosenone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class ChosenOne extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ---- Tweak these ----
    private static final String PREFIX = "&5&l[Chosen] &r";
    private static final int INTRO_FRAMES = 20;      // frames of pure scramble before letters lock (1 frame = 2 ticks)
    private static final int LOCK_EVERY = 5;         // frames between each letter locking in
    private static final int CHAT_ROLL_EVERY = 3;    // send the rolling text to chat every N frames (0 = never)
    private static final int FLOAT_TICKS = 120;      // how long the chosen player floats before being chained
    private static final int HOLD_TICKS = 400;       // how long the chains hold (400 ticks = 20 seconds)
    private static final float DUST_SIZE = 1.6f;     // chain thickness (medium). Smaller = thinner, up to 4
    private static final Particle.DustOptions GOLD =
            new Particle.DustOptions(Color.fromRGB(255, 200, 40), DUST_SIZE);
    private static final Particle.DustOptions RED =
            new Particle.DustOptions(Color.fromRGB(190, 15, 15), DUST_SIZE);
    private static final String CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    // ---------------------

    private final Random rng = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean running = false;
    private UUID chainedId = null;

    // =====================================================================
    //  Setup
    // =====================================================================

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        register("chosen");
        register("chainstest");
        getLogger().info("ChosenOne enabled. Commands: /chosen [player|stop], /chainstest [player] [seconds]");
    }

    @Override
    public void onDisable() {
        stopAll();
    }

    private void register(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml!");
            return;
        }
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
    }

    // =====================================================================
    //  Commands
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("chainstest")) {
            return runChainsTest(sender, args);
        }
        if (!name.equals("chosen")) return false;

        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            stopAll();
            sender.sendMessage(text("&aRitual stopped and everything reset."));
            return true;
        }

        if (running) {
            sender.sendMessage(text("&cA ritual is already running. Use /chosen stop to cancel it."));
            return true;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            sender.sendMessage(text("&cNobody is online."));
            return true;
        }

        Player chosen;
        if (args.length >= 1) {
            chosen = Bukkit.getPlayerExact(args[0]);
            if (chosen == null) {
                sender.sendMessage(text("&cThat player is not online."));
                return true;
            }
        } else {
            chosen = online.get(rng.nextInt(online.size()));
        }

        startRoll(chosen);
        return true;
    }

    // /chainstest [player] [seconds] : runs only the chains + circle + snap
    private boolean runChainsTest(CommandSender sender, String[] args) {
        if (running) {
            sender.sendMessage(text("&cA ritual is already running. Use /chosen stop to cancel it."));
            return true;
        }

        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(text("&cThat player is not online."));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(text("&cUsage: /chainstest <player> [seconds]"));
            return true;
        }

        int holdTicks = HOLD_TICKS;
        if (args.length >= 2) {
            try {
                int secs = Integer.parseInt(args[1]);
                holdTicks = Math.max(2, Math.min(secs, 60)) * 20;
            } catch (NumberFormatException ex) {
                sender.sendMessage(text("&cSeconds must be a number."));
                return true;
            }
        }

        running = true;
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, holdTicks + 300, 0, false, false));
        sender.sendMessage(text("&6Testing chains on &e" + target.getName() + "&6 for " + (holdTicks / 20) + "s."));
        startChains(target, holdTicks);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (cmd.getName().equalsIgnoreCase("chosen") && "stop".startsWith(prefix)) {
                out.add("stop");
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }

    // =====================================================================
    //  Events
    // =====================================================================

    // Holds the chained player in place (they can still look around)
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (chainedId == null || !e.getPlayer().getUniqueId().equals(chainedId)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    // Never leave someone saved with "no gravity" if they leave mid-chain
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (chainedId != null && e.getPlayer().getUniqueId().equals(chainedId)) {
            e.getPlayer().setGravity(true);
        }
    }

    // =====================================================================
    //  Stage 1: the slot-machine name roll
    // =====================================================================

    private void startRoll(Player chosen) {
        running = true;
        final String name = chosen.getName();

        every(2L, new BukkitRunnable() {
            int frame = 0;
            int lastLocked = 0;

            @Override
            public void run() {
                if (!chosen.isOnline()) {
                    broadcast(PREFIX + "&cThe chosen one fled... the ritual fades.");
                    running = false;
                    cancel();
                    return;
                }

                if (frame == 0) {
                    broadcast(PREFIX + "&dThe ancient powers stir...");
                } else if (frame == 10) {
                    broadcast(PREFIX + "&5Choosing one worthy...");
                }

                int locked = frame < INTRO_FRAMES ? 0 : (frame - INTRO_FRAMES) / LOCK_EVERY;

                if (locked >= name.length()) {
                    cancel();
                    reveal(chosen);
                    return;
                }

                String display = "&6&l" + name.substring(0, locked)
                        + "&7&l" + scramble(name.length() - locked);

                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendActionBar(text(display));
                    showTitle(p, "&5Choosing one worthy...", display, 0, 10, 0);
                    play(p, "block.note_block.hat", 0.6f, 1.5f);
                }

                if (frame >= INTRO_FRAMES && locked > lastLocked) {
                    float pitch = Math.min(2f, 0.8f + 1.0f * locked / Math.max(1, name.length()));
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        play(p, "entity.experience_orb.pickup", 1f, pitch);
                    }
                }
                lastLocked = locked;

                if (CHAT_ROLL_EVERY > 0 && frame >= 10 && frame % CHAT_ROLL_EVERY == 0) {
                    broadcast(PREFIX + display);
                }

                frame++;
            }
        });
    }

    // =====================================================================
    //  Stage 2: reveal, dragon growl, float
    // =====================================================================

    private void reveal(Player chosen) {
        String name = chosen.getName();

        broadcast(PREFIX + "&e&m      &r &6&l" + name + " &r&e&m      ");
        broadcast(PREFIX + "&d" + name + " &ehas been chosen!");
        for (Player p : Bukkit.getOnlinePlayers()) {
            showTitle(p, "&6&l" + name, "&dThe Chosen One", 5, 70, 20);
            p.sendActionBar(text("&6&l" + name));
            play(p, "entity.ender_dragon.growl", 1f, 1f);
        }

        // Lightning flash (visual only, no damage or fire)
        chosen.getWorld().strikeLightningEffect(chosen.getLocation());

        // Float now, slow falling so they land safely after the chains snap
        chosen.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, FLOAT_TICKS, 1, false, false));
        chosen.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                FLOAT_TICKS + HOLD_TICKS + 300, 0, false, false));
        chosen.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                FLOAT_TICKS + HOLD_TICKS + 100, 0, false, false));

        every(2L, new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!chosen.isOnline()) {
                    running = false;
                    cancel();
                    return;
                }
                if (t >= FLOAT_TICKS / 2) {
                    cancel();
                    startChains(chosen, HOLD_TICKS);
                    return;
                }
                Location base = chosen.getLocation();
                double angle = t * 0.6;
                for (int i = 0; i < 3; i++) {
                    double a = angle + i * (Math.PI * 2 / 3);
                    Location pt = base.clone().add(Math.cos(a) * 1.2, 0.2 + (t % 20) * 0.1, Math.sin(a) * 1.2);
                    chosen.getWorld().spawnParticle(Particle.END_ROD, pt, 1, 0, 0, 0, 0);
                    chosen.getWorld().spawnParticle(Particle.PORTAL, pt, 4, 0.1, 0.1, 0.1, 0.2);
                }
                t++;
            }
        });
    }

    // =====================================================================
    //  Stage 3: golden dust chains + summoning circle, then SNAP
    // =====================================================================

    private void startChains(Player chosen, int holdTicks) {
        chainedId = chosen.getUniqueId();

        // The circle stands behind the player, opposite the direction they were facing
        Vector facing = chosen.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.0001) facing = new Vector(0, 0, 1);
        final Vector back = facing.normalize().multiply(-1);
        final Location hold = chosen.getLocation().clone();

        chosen.setGravity(false);
        chosen.setVelocity(new Vector(0, 0, 0));

        broadcast(PREFIX + "&6Golden chains bind &e" + chosen.getName() + "&6!");
        playAt(chosen.getLocation(), "block.chain.place", 2f, 0.8f);

        final int total = holdTicks / 2;

        every(2L, new BukkitRunnable() {
            int t = 0; // one step = 2 ticks

            @Override
            public void run() {
                if (!chosen.isOnline()) {
                    release(chosen);
                    cancel();
                    return;
                }

                if (t >= total) {
                    cancel();
                    snap(chosen);
                    return;
                }

                chosen.setVelocity(new Vector(0, 0, 0));

                if (t % 2 == 0) {
                    double progress = Math.min(1.0, t / 10.0); // chains extend over the first second
                    drawCircle(hold.getWorld(), hold, back, progress, t * 0.02);
                    drawChains(chosen.getLocation(), progress);
                }

                if (t % 10 == 0) {
                    int secsLeft = (int) Math.ceil((total - t) * 2 / 20.0);
                    chosen.sendActionBar(text("&6&lBound by golden chains &e" + secsLeft + "s"));
                }

                if (t % 20 == 0 && t > 0) {
                    playAt(chosen.getLocation(), "block.chain.step", 1.5f, 0.7f);
                }

                t++;
            }
        });
    }

    private void snap(Player chosen) {
        Location loc = chosen.getLocation();
        World w = loc.getWorld();

        // Gold + red burst
        for (int i = 0; i < 250; i++) {
            double x = loc.getX() + (rng.nextDouble() - 0.5) * 7;
            double y = loc.getY() + rng.nextDouble() * 3.5 - 1;
            double z = loc.getZ() + (rng.nextDouble() - 0.5) * 7;
            w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, i % 2 == 0 ? GOLD : RED);
        }
        w.spawnParticle(Particle.CRIT, loc.clone().add(0, 1, 0), 80, 1.5, 1, 1.5, 0.4);

        // Dragon roar for everyone + metal snapping
        for (Player p : Bukkit.getOnlinePlayers()) {
            play(p, "entity.ender_dragon.growl", 1f, 0.9f);
            play(p, "block.chain.break", 2f, 0.6f);
            play(p, "item.shield.break", 1f, 0.7f);
            showTitle(p, "&6&lSNAP!", "&e" + chosen.getName() + " broke free", 0, 40, 10);
        }
        broadcast(PREFIX + "&6&lThe chains SNAP! &e" + chosen.getName() + " broke free!");

        release(chosen);
    }

    private void release(Player chosen) {
        chosen.setGravity(true);
        chainedId = null;
        running = false;
    }

    // Cancel everything and put things back to normal
    private void stopAll() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (chainedId != null) {
            Player p = Bukkit.getPlayer(chainedId);
            if (p != null) p.setGravity(true);
            chainedId = null;
        }
        running = false;
    }

    // =====================================================================
    //  Particle drawing
    // =====================================================================

    private void drawChains(Location center, double progress) {
        World w = center.getWorld();
        double[] bodyY = {0.3, 1.0, 1.5, 0.6, 1.2, 0.9};
        for (int k = 0; k < 6; k++) {
            double ang = k * Math.PI / 3;
            Vector anchor = new Vector(
                    center.getX() + Math.cos(ang) * 3.5,
                    center.getY() + (k % 2 == 0 ? -1.2 : 2.4),
                    center.getZ() + Math.sin(ang) * 3.5);
            Vector target = new Vector(
                    center.getX() + Math.cos(ang) * 0.3,
                    center.getY() + bodyY[k],
                    center.getZ() + Math.sin(ang) * 0.3);
            drawChain(w, anchor, target, progress);
        }
    }

    // Alternating face-on rings and edge-on bars, built from gold dust
    private void drawChain(World w, Vector anchor, Vector target, double progress) {
        Vector diff = target.clone().subtract(anchor);
        double len = diff.length();
        if (len < 0.01) return;
        Vector dir = diff.clone().normalize();
        Vector up = Math.abs(dir.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = dir.clone().crossProduct(up).normalize();

        int n = Math.max(1, (int) (len / 0.4));
        int shown = (int) (n * progress);

        for (int i = 0; i <= shown; i++) {
            double tt = (double) i / n;
            Vector p = anchor.clone().add(diff.clone().multiply(tt));
            p.setY(p.getY() - Math.sin(Math.PI * tt) * 0.35); // slight sag

            if (i % 2 == 0) {
                for (int j = 0; j < 8; j++) {
                    double a = j / 8.0 * Math.PI * 2;
                    Vector q = p.clone()
                            .add(dir.clone().multiply(Math.cos(a) * 0.22))
                            .add(u.clone().multiply(Math.sin(a) * 0.12));
                    dust(w, q, GOLD);
                }
            } else {
                for (int j = -1; j <= 1; j++) {
                    dust(w, p.clone().add(dir.clone().multiply(j * 0.1)), GOLD);
                }
            }
        }
    }

    // Vertical double ring + rotating rune ticks + inverted pentagram, standing behind the player
    private void drawCircle(World w, Location hold, Vector back, double p, double rot) {
        Vector up = new Vector(0, 1, 0);
        Vector right = back.clone().crossProduct(up).normalize();
        Vector c = hold.toVector().add(back.clone().multiply(1.3)).add(new Vector(0, 1.0, 0));
        double r1 = 2.5, r2 = 2.2;

        for (double radius : new double[]{r1, r2}) {
            int n = (int) (2 * Math.PI * radius / 0.2);
            int lim = (int) (n * p);
            for (int i = 0; i < lim; i++) {
                double a = (double) i / n * 2 * Math.PI;
                plotCircle(w, c, right, up, Math.cos(a) * radius, Math.sin(a) * radius);
            }
        }

        for (int k = 0; k < 12; k++) {
            if (k / 12.0 > p) break;
            double a = k / 12.0 * 2 * Math.PI + rot;
            for (double r = r2 + 0.1; r < r1; r += 0.12) {
                plotCircle(w, c, right, up, Math.cos(a) * r, Math.sin(a) * r);
            }
        }

        double[][] v = new double[5][2];
        for (int k = 0; k < 5; k++) {
            double a = -Math.PI / 2 + k * 2 * Math.PI / 5; // one point faces down = inverted
            v[k][0] = Math.cos(a) * r2;
            v[k][1] = Math.sin(a) * r2;
        }
        for (int k = 0; k < 5; k++) {
            double[] a = v[k];
            double[] b = v[(k + 2) % 5];
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int n = Math.max(1, (int) (len / 0.2));
            int lim = (int) (n * p);
            for (int i = 0; i <= lim; i++) {
                double f = (double) i / n;
                plotCircle(w, c, right, up, a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f);
            }
        }
    }

    private void plotCircle(World w, Vector c, Vector right, Vector up, double x, double y) {
        Vector v = c.clone().add(right.clone().multiply(x)).add(up.clone().multiply(y));
        dust(w, v, RED);
    }

    private void dust(World w, Vector v, Particle.DustOptions opts) {
        w.spawnParticle(Particle.DUST, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, opts);
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    // Schedules a repeating task and remembers it so /chosen stop can cancel it
    private void every(long periodTicks, BukkitRunnable r) {
        tasks.add(r.runTaskTimer(this, 0L, periodTicks));
    }

    private String scramble(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(CHARS.charAt(rng.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    // Looks sounds up by key so a renamed constant can never break the build
    private Sound sound(String key) {
        return Registry.SOUNDS.get(NamespacedKey.minecraft(key));
    }

    private void play(Player p, String key, float volume, float pitch) {
        Sound s = sound(key);
        if (s != null) p.playSound(p.getLocation(), s, volume, pitch);
    }

    private void playAt(Location loc, String key, float volume, float pitch) {
        Sound s = sound(key);
        if (s != null) loc.getWorld().playSound(loc, s, volume, pitch);
    }

    private Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private void broadcast(String legacy) {
        Bukkit.broadcast(text(legacy));
    }

    private void showTitle(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(text(title), text(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fadeIn * 50L),
                        Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(fadeOut * 50L))));
    }
}
