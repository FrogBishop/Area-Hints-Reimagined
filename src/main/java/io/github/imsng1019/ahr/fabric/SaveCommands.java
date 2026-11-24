package io.github.imsng1019.ahr.fabric;

import static net.minecraft.commands.Commands.*;

import com.google.gson.*;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.*;

import io.github.imsng1019.ahr.data.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.util.*;

public final class SaveCommands {
    private SaveCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registries, env) -> {
                    var root = literal("ahr");
                    for (String cmd : List.of("reload", "add", "del", "aabb")) {
                        var branch =
                                literal(cmd)
                                        .requires(
                                                s ->
                                                        s.permissions()
                                                                        .hasPermission(
                                                                                Permissions
                                                                                        .COMMANDS_GAMEMASTER)
                                                                || (s.getEntity()
                                                                                instanceof
                                                                                ServerPlayer p
                                                                        && AreaHints.author(
                                                                                s.getServer(), p)));
                        if (cmd.equals("reload")) branch.executes(c -> run(c, cmd, ""));
                        else
                            branch.then(
                                    argument("arguments", StringArgumentType.greedyString())
                                            .executes(
                                                    c ->
                                                            run(
                                                                    c,
                                                                    cmd,
                                                                    StringArgumentType.getString(
                                                                            c, "arguments"))));
                        root.then(branch);
                    }
                    dispatcher.register(root);
                });
    }

    public static List<String> readPath(StringReader reader) {
        reader.skipWhitespace();
        int start = reader.getCursor();
        if (!reader.canRead() || reader.read() != '[')
            throw new IllegalArgumentException("Expected a JSON path array");
        boolean string = false;
        while (reader.canRead()) {
            char c = reader.read();
            if (string && c == '\\' && reader.canRead()) reader.skip();
            else if (c == '"') string = !string;
            else if (!string && c == ']')
                return Json.path(
                        Json.parse(reader.getString().substring(start, reader.getCursor())));
        }
        throw new IllegalArgumentException("Unclosed path array");
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String cmd, String args)
            throws CommandSyntaxException {
        var src = ctx.getSource();
        var server = src.getServer();
        var world = AreaHints.world(server);
        String id = src.getLevel().dimension().identifier().toString();
        var reader = new StringReader(args);
        try {
            switch (cmd) {
                case "reload" -> world.reload();
                case "add" -> {
                    var path = readPath(reader);
                    reader.skipWhitespace();
                    if (!reader.canRead())
                        throw new IllegalArgumentException("Expected fallback name and predicate");
                    String fallback = reader.readString();
                    reader.skipWhitespace();
                    var fields = new JsonObject();
                    var name = new JsonObject();
                    name.addProperty("fallback", fallback);
                    fields.add("name", name);
                    fields.add("predicate", Json.parse(reader.getRemaining()).getAsJsonObject());
                    world.edit(id, null, root -> WorldFiles.put(root, null, path, fields));
                }
                case "del" -> {
                    var path = readPath(reader);
                    String scope = reader.getRemaining().trim();
                    if (!scope.isEmpty() && !scope.equals("subtree"))
                        throw new IllegalArgumentException("Expected subtree");
                    world.edit(
                            id,
                            null,
                            root -> WorldFiles.delete(root, path, scope.equals("subtree")));
                }
                case "aabb" -> {
                    String scope = reader.readUnquotedString();
                    if (!scope.equals("self") && !scope.equals("subtree"))
                        throw new IllegalArgumentException("Choose self or subtree");
                    var path = readPath(reader);
                    if (!reader.getRemaining().isBlank())
                        throw new IllegalArgumentException("Unexpected trailing arguments");
                    world.edit(
                            id,
                            null,
                            root -> WorldFiles.recalculate(root, path, scope.equals("subtree")));
                }
                default -> throw new IllegalArgumentException("Unknown command");
            }
            AreaHints.publish(server);
            src.sendSuccess(() -> Component.translatable("ahr.command.saved"), false);
            return 1;
        } catch (RuntimeException | IOException e) {
            throw new SimpleCommandExceptionType(
                            Component.literal(
                                    Objects.toString(e.getMessage(), "Invalid region data")))
                    .create();
        }
    }
}
