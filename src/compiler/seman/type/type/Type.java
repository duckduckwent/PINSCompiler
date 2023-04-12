/**
 * @ Author: turk
 * @ Description:
 */

package compiler.seman.type.type;

import static common.RequireNonNull.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import common.Constants;

public abstract class Type {
    /**
     * Vrne velikost tipa v bytih.
     */
    public abstract int sizeInBytes();

    /**
     * Vrne velikost tipa, če je le-ta uporabljen kot parameter/argument.
     * 
     * V primeru prenosa po referenci, je velikost tipa enaka
     * velikosti kazalca.
     */
    public abstract int sizeInBytesAsParam();

    /**
     * Ali tip strukturno enak drugemu tipu.
     */
    public abstract boolean equals(Type t);

    // ------------------------------------

    /**
     * Preveri, ali je tip atomaren tip.
     */
    public boolean isAtom() {
        return this instanceof Atom;
    }

    /**
     * Preveri, ali je tip `LOG`.
     */
    public boolean isLog() {
        return (this instanceof Atom a) && a.kind == Atom.Kind.LOG;
    }

    /**
     * Preveri, ali je tip `STR`.
     */
    public boolean isStr() {
        return (this instanceof Atom a) && a.kind == Atom.Kind.STR;
    }

    /**
     * Preveri, ali je tip `INT`.
     */
    public boolean isInt() {
        return (this instanceof Atom a) && a.kind == Atom.Kind.INT;
    }

    /**
     * Če je tip atomaren tip, ga vrne. Sicer
     * vrne `Optional.empty()`.
     */
    public Optional<Atom> asAtom() {
        if (this instanceof Atom t) return Optional.of(t);
        return Optional.empty();
    }

    /**
     * Preveri, ali je tip tabela.
     */
    public boolean isArray() {
        return this instanceof Array;
    }

    /**
     * Če je tip tabela, jo vrne. Sicer
     * vrne `Optional.empty()`.
     */
    public Optional<Array> asArray() {
        if (this instanceof Array t) return Optional.of(t);
        return Optional.empty();
    }

    /**
     * Preveri, ali je tip funkcijski tip.
     */
    public boolean isFunction() {
        return this instanceof Function;
    }

    /**
     * Če je tip funkcijski tip, ga vrne. Sicer
     * vrne `Optional.empty()`.
     */
    public Optional<Function> asFunction() {
        if (this instanceof Function t) return Optional.of(t);
        return Optional.empty();
    }

    // ------------------------------------

    // Razredi, ki predstavljajo različne podatkovne tipe.

    /**
     * Atomarni podatkovni tip.
     */
    public static class Atom extends Type {
        /**
         * Vrsta podatkovnega tipa.
         */
        public final Kind kind;

        public Atom(Kind kind) {
            requireNonNull(kind);
            this.kind = kind;
        }

        @Override
        public int sizeInBytes() {
            return kind.size;
        }

        @Override
        public int sizeInBytesAsParam() {
            return kind.size;
        }

        @Override
        public boolean equals(Type t) {
            return t.asAtom().isPresent() && this.kind == t.asAtom().get().kind;
        }

        @Override
        public String toString() {
            return switch (kind) {
                case INT: yield "int";
                case STR: yield "str";
                case LOG: yield "log";
                case VOID: yield "void";
            };
        }

        public static enum Kind {
            LOG(Constants.WordSize), INT(Constants.WordSize), STR(Constants.WordSize), VOID(0);

            final int size;

            Kind(int size) {
                this.size = size;
            }
        }
    }

    /**
     * Podatkovni tip, ki predstavlja tabelo.
     */
    public static class Array extends Type {
        /**
         * Velikost (št. elementov) tabele.
         */
        public final int size;

        /**
         * Podatkovni tip elementov.
         */
        public final Type type;

        public Array(int size, Type type) {
            requireNonNull(type);
            this.size = size;
            this.type = type;
        }

        @Override
        public int sizeInBytes() {
            return size * type.sizeInBytes();
        }

        @Override
        public int sizeInBytesAsParam() {
            return Constants.WordSize;
        }

        public int elementSizeInBytes() {
            return type.sizeInBytes();
        }

        @Override
        public boolean equals(Type t) {
            Optional<Array> optArray = t.asArray();
            return optArray.isPresent() && this.size == optArray.get().size && this.type.equals(optArray.get().type);
        }

        @Override
        public String toString() {
            return "ARR("+size+","+type.toString()+")";
        }
    }

    /**
     * Podatkovni tip, ki predstavlja funkcijo.
     */
    public static class Function extends Type {
        /**
         * Tipi parametrov.
         */
        public final List<Type> parameters;
        
        /**
         * Tip, ki ga funkcija vrača.
         */
        public final Type returnType;

        public Function(List<Type> parameters, Type returnType) {
            requireNonNull(parameters);
            requireNonNull(returnType);
            this.parameters = parameters;
            this.returnType = returnType;
        }

        @Override
        public int sizeInBytes() {
            int bytes = returnType.sizeInBytes();
            for (var parameter : parameters) {
                bytes += parameter.sizeInBytesAsParam();
            }
            return bytes;
        }

        @Override
        public int sizeInBytesAsParam() {
            return Constants.WordSize;
        }

        @Override
        public boolean equals(Type t) {
            Optional<Function> optFunction = t.asFunction();

            // Če je 't' funkcijski tip in ima enako število parametrov, je potencialno lahko enakega tipa, sicer pa ne
            if (!(optFunction.isPresent() && this.parameters.size() == optFunction.get().parameters.size()))
                return false;

            // Če se tip rezultata funkcij ne ujemata, nista strukturno enaki
            if (!this.returnType.equals(optFunction.get().returnType))
                return false;

            // Za vsaka para parametrov preveri, ali sta strukturno enaka, če vsaj en par ni, potem funkciji nista enaki
            for (int i = 0; i < this.parameters.size(); i++)
                if (!this.parameters.get(i).equals(optFunction.get().parameters.get(i)))
                    return false;
            return true;
        }

        @Override
        public String toString() {
            var params = parameters.stream()
                .map(t -> t.toString())
                .collect(Collectors.joining(", "));
            return "(" +  params + ") -> " + returnType.toString();
        }
    }
}
