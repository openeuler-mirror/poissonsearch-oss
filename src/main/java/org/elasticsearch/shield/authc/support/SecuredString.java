/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.support;

import org.elasticsearch.ElasticsearchException;

import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * This is not a string but a CharSequence that can be cleared of its memory.  Important for handling passwords.
 *
 * Not thread safe There is a chance that the chars could be cleared while doing operations on the chars.
 * <p/>
 * TODO: dot net's SecureString implementation does some obfuscation of the password to prevent gleaming passwords
 * from memory dumps.  (this is hard as dot net uses windows system crypto.  Thats probably the reason java still doesn't have it)
 */
public class SecuredString implements CharSequence {

    private final char[] chars;
    private boolean cleared = false;

    /**
     * Note: the passed in chars are not duplicated, but used directly for performance/optimization.  DO NOT
     * modify or clear the chars after it has been passed into this constructor.
     */
    public SecuredString(char[] chars) {
        this.chars = new char[chars.length];
        System.arraycopy(chars, 0, this.chars, 0, chars.length);
    }

    /**
     * This constructor is used internally for the concatenate method.  It DOES duplicate the passed in array, unlike
     * the public constructor
     */
    private SecuredString(char[] chars, int start, int end) {
        this.chars = new char[end - start];
        System.arraycopy(chars, start, this.chars, 0, this.chars.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;

        if (o instanceof SecuredString) {
            SecuredString that = (SecuredString) o;

            if (cleared != that.cleared) return false;
            if (!Arrays.equals(chars, that.chars)) return false;

            return true;
        } else if (o instanceof CharSequence) {
            CharSequence that = (CharSequence) o;
            if (cleared) return false;
            if (chars.length != that.length()) return false;

            for (int i = 0; i < chars.length; i++) {
                if (chars[i] != that.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(chars);
        result = 31 * result + (cleared ? 1 : 0);
        return result;
    }

    /**
     * Note: This is a dangerous call that exists for performance/optimization
     * DO NOT modify the array returned by this method.  To clear the array call SecureString.clear().
     *
     * @return the internal characters that MUST NOT be cleared manually
     */
    public char[] internalChars() {
        throwIfCleared();
        return chars;
    }

    /**
     * @return utf8 encoded bytes
     */
    public byte[] utf8Bytes() {
        throwIfCleared();
        return CharArrays.toUtf8Bytes(chars);
    }

    @Override
    public int length() {
        throwIfCleared();
        return chars.length;
    }

    @Override
    public char charAt(int index) {
        throwIfCleared();
        return chars[index];
    }

    @Override
    public SecuredString subSequence(int start, int end) {
        throwIfCleared();
        return new SecuredString(this.chars, start, end);
    }

    /**
     * Manually clear the underlying array holding the characters
     */
    public void clear() {
        cleared = true;
        Arrays.fill(chars, (char) 0);
    }

    @Override
    public void finalize() throws Throwable {
        clear();
        super.finalize();
    }

    /**
     * @param toAppend String to combine with this SecureString
     * @return a new SecureString with toAppend concatenated
     */
    public SecuredString concat(CharSequence toAppend) {
        throwIfCleared();

        CharBuffer buffer = CharBuffer.allocate(chars.length + toAppend.length());
        buffer.put(chars);
        for (int i = 0; i < toAppend.length(); i++) {
            buffer.put(i + chars.length, toAppend.charAt(i));
        }
        return new SecuredString(buffer.array());
    }

    private void throwIfCleared() {
        if (cleared) {
            throw new ElasticsearchException("attempt to use cleared password");
        }
    }
}
