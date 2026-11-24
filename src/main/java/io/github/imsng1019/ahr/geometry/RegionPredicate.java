package io.github.imsng1019.ahr.geometry;

import com.google.gson.JsonObject;

import io.github.imsng1019.ahr.data.Json;

import java.util.*;

/** Compiles once per region. Forward jumps implement short-circuit evaluation. */
public final class RegionPredicate {
    private enum Op {
        READ,
        NOT,
        AND,
        OR
    }

    private record Instruction(Op op, int argument) {}

    private record Pending(char operator, int jump) {}

    private record Extent(Bounds box, boolean universal) {}

    private final List<Primitive> primitives;
    private final List<Instruction> program = new ArrayList<>();
    private final Bounds bounds;

    public RegionPredicate(JsonObject json, boolean editing) {
        primitives =
                json.getAsJsonArray("primitives").asList().stream()
                        .map(e -> Primitive.read(e, editing))
                        .toList();
        bounds = new Compiler().compile(Json.string(json.get("expression")));
    }

    public Bounds bounds() {
        return bounds;
    }

    public boolean contains(Point point) {
        byte[] memo = new byte[primitives.size()];
        boolean value = false;
        int pc = 0;
        while (pc < program.size()) {
            Instruction step = program.get(pc++);
            int argument = step.argument;
            switch (step.op) {
                case READ -> {
                    if (memo[argument] == 0)
                        memo[argument] = (byte) (primitives.get(argument).contains(point) ? 1 : -1);
                    value = memo[argument] == 1;
                }
                case NOT -> value = !value;
                case AND -> {
                    if (!value) pc = argument;
                }
                case OR -> {
                    if (value) pc = argument;
                }
            }
        }
        return value;
    }

    private final class Compiler {
        private final Deque<Pending> operators = new ArrayDeque<>();
        private final Deque<Extent> operands = new ArrayDeque<>();
        private final Extent[] referenced = new Extent[primitives.size()];

        Bounds compile(String text) {
            boolean expectOperand = true;
            for (int pos = 0; pos < text.length(); ) {
                char token = text.charAt(pos++);
                if (Character.isWhitespace(token)) continue;
                if (expectOperand) {
                    if (token == '!' || token == '(') {
                        operators.push(new Pending(token, -1));
                        continue;
                    }
                    if (!digit(token)) throw error("Expected a primitive index", pos - 1);
                    int start = pos - 1;
                    while (pos < text.length() && digit(text.charAt(pos))) pos++;
                    int index = Integer.parseInt(text, start, pos, 10);
                    if (index >= primitives.size())
                        throw error("Unknown primitive " + index, start);
                    if (referenced[index] == null) {
                        var part = primitives.get(index);
                        referenced[index] = new Extent(part.bounds(), part.coversDomain());
                    }
                    operands.push(referenced[index]);
                    program.add(new Instruction(Op.READ, index));
                    expectOperand = false;
                } else if (token == ')') {
                    while (!operators.isEmpty() && operators.peek().operator != '(')
                        finishOperator();
                    if (operators.isEmpty()) throw error("Unexpected ')'", pos - 1);
                    operators.pop();
                } else if (token == '&' || token == '|') {
                    while (!operators.isEmpty()
                            && precedence(operators.peek().operator) >= precedence(token))
                        finishOperator();
                    operators.push(new Pending(token, program.size()));
                    program.add(new Instruction(token == '&' ? Op.AND : Op.OR, -1));
                    expectOperand = true;
                } else throw error("Expected an operator", pos - 1);
            }
            if (expectOperand) throw error("Expected a primitive index", text.length());
            while (!operators.isEmpty()) {
                if (operators.peek().operator == '(') throw error("Expected ')'", text.length());
                finishOperator();
            }
            return operands.pop().box;
        }

        private void finishOperator() {
            var pending = operators.pop();
            var right = operands.pop();
            if (pending.operator == '!') {
                program.add(new Instruction(Op.NOT, 0));
                operands.push(
                        new Extent(right.universal ? Bounds.EMPTY : Bounds.ALL, right.box.empty()));
            } else {
                Op kind = program.get(pending.jump).op;
                program.set(pending.jump, new Instruction(kind, program.size()));
                var left = operands.pop();
                operands.push(
                        kind == Op.AND
                                ? new Extent(
                                        left.box.intersect(right.box),
                                        left.universal && right.universal)
                                : new Extent(
                                        left.box.union(right.box),
                                        left.universal || right.universal));
            }
        }
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int precedence(char op) {
        return switch (op) {
            case '!' -> 3;
            case '&' -> 2;
            case '|' -> 1;
            default -> 0;
        };
    }

    private static IllegalArgumentException error(String message, int pos) {
        return new IllegalArgumentException(message + " at expression offset " + pos);
    }
}
