/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.crypto;

import com.google.common.base.Charsets;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;

/**
 *
 */
public class InternalCryptoServiceTests extends ElasticsearchTestCase {

    private ResourceWatcherService watcherService;
    private Settings settings;
    private Environment env;
    private Path keyFile;
    private ThreadPool threadPool;

    @Before
    public void init() throws Exception {
        keyFile = createTempDir().resolve("system_key");
        Files.write(keyFile, InternalCryptoService.generateKey());
        settings = Settings.builder()
                .put("shield.system_key.file", keyFile.toAbsolutePath())
                .put("watcher.interval.high", "2s")
                .put("path.home", createTempDir())
                .build();
        env = new Environment(settings);
        threadPool = new ThreadPool("test");
        watcherService = new ResourceWatcherService(settings, threadPool);
        watcherService.start();
    }

    @After
    public void shutdown() throws InterruptedException {
        watcherService.stop();
        terminate(threadPool);
    }

    @Test
    public void testSigned() throws Exception {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        assertThat(service.signed(signed), is(true));
    }

    @Test
    public void testSignAndUnsign() throws Exception {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        assertThat(text.equals(signed), is(false));
        String text2 = service.unsignAndVerify(signed);
        assertThat(text, equalTo(text2));
    }

    @Test
    public void testSignAndUnsign_NoKeyFile() throws Exception {
        InternalCryptoService service = new InternalCryptoService(Settings.EMPTY, env, watcherService).start();
        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        assertThat(text, equalTo(signed));
        text = service.unsignAndVerify(signed);
        assertThat(text, equalTo(signed));
    }

    @Test
    public void testTamperedSignature() throws Exception {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        int i = signed.indexOf("$$", 2);
        int length = Integer.parseInt(signed.substring(2, i));
        String fakeSignature = randomAsciiOfLength(length);
        String fakeSignedText = "$$" + length + "$$" + fakeSignature + signed.substring(i + 2 + length);

        try {
            service.unsignAndVerify(fakeSignedText);
        } catch (SignatureException e) {
            assertThat(e.getMessage(), is(equalTo("tampered signed text")));
            assertThat(e.getCause(), is(nullValue()));
        }
    }

    @Test
    public void testTamperedSignatureOneChar() throws Exception {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        int i = signed.indexOf("$$", 2);
        int length = Integer.parseInt(signed.substring(2, i));
        StringBuilder fakeSignature = new StringBuilder(signed.substring(i + 2, i + 2 + length));
        fakeSignature.setCharAt(randomIntBetween(0, fakeSignature.length() - 1), randomAsciiOfLength(1).charAt(0));

        String fakeSignedText = "$$" + length + "$$" + fakeSignature.toString() + signed.substring(i + 2 + length);

        try {
            service.unsignAndVerify(fakeSignedText);
        } catch (SignatureException e) {
            assertThat(e.getMessage(), is(equalTo("tampered signed text")));
            assertThat(e.getCause(), is(nullValue()));
        }
    }

    @Test
    public void testTamperedSignatureLength() throws Exception {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        int i = signed.indexOf("$$", 2);
        int length = Integer.parseInt(signed.substring(2, i));
        String fakeSignature = randomAsciiOfLength(length);

        // Smaller sig length
        String fakeSignedText = "$$" + randomIntBetween(0, length - 1) + "$$" + fakeSignature + signed.substring(i + 2 + length);

        try {
            service.unsignAndVerify(fakeSignedText);
        } catch (SignatureException e) {
            assertThat(e.getMessage(), is(equalTo("tampered signed text")));
        }

        // Larger sig length
        fakeSignedText = "$$" + randomIntBetween(length + 1, Integer.MAX_VALUE) + "$$" + fakeSignature + signed.substring(i + 2 + length);
        try {
            service.unsignAndVerify(fakeSignedText);
        } catch (SignatureException e) {
            assertThat(e.getMessage(), is(equalTo("tampered signed text")));
            assertThat(e.getCause(), is(nullValue()));
        }
    }

    @Test
    public void testEncryptionAndDecryptionChars() {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));
        final char[] chars = randomAsciiOfLengthBetween(0, 1000).toCharArray();
        final char[] encrypted = service.encrypt(chars);
        assertThat(encrypted, notNullValue());
        assertThat(Arrays.equals(encrypted, chars), is(false));

        final char[] decrypted = service.decrypt(encrypted);
        assertThat(Arrays.equals(chars, decrypted), is(true));
    }

    @Test
    public void testEncryptionAndDecryptionBytes() {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));
        final byte[] bytes = randomByteArray();
        final byte[] encrypted = service.encrypt(bytes);
        assertThat(encrypted, notNullValue());
        assertThat(Arrays.equals(encrypted, bytes), is(false));

        final byte[] decrypted = service.decrypt(encrypted);
        assertThat(Arrays.equals(bytes, decrypted), is(true));
    }

    @Test
    public void testEncryptionAndDecryptionCharsWithoutKey() {
        InternalCryptoService service = new InternalCryptoService(Settings.EMPTY, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(false));
        final char[] chars = randomAsciiOfLengthBetween(0, 1000).toCharArray();
        final char[] encryptedChars = service.encrypt(chars);
        final char[] decryptedChars = service.decrypt(encryptedChars);
        assertThat(chars, equalTo(encryptedChars));
        assertThat(chars, equalTo(decryptedChars));
    }

    @Test
    public void testEncryptionAndDecryptionBytesWithoutKey() {
        InternalCryptoService service = new InternalCryptoService(Settings.EMPTY, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(false));
        final byte[] bytes = randomByteArray();
        final byte[] encryptedBytes = service.encrypt(bytes);
        final byte[] decryptedBytes = service.decrypt(bytes);
        assertThat(bytes, equalTo(encryptedBytes));
        assertThat(decryptedBytes, equalTo(encryptedBytes));
    }

    @Test
    public void testEncryptionEnabledWithKey() {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));
    }

    @Test
    public void testEncryptionEnabledWithoutKey() {
        InternalCryptoService service = new InternalCryptoService(Settings.EMPTY, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(false));
    }

    @Test
    public void testChangingAByte() {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));
        // We need at least one byte to test changing a byte, otherwise output is always the same
        final byte[] bytes = randomByteArray(1);
        final byte[] encrypted = service.encrypt(bytes);
        assertThat(encrypted, notNullValue());
        assertThat(Arrays.equals(encrypted, bytes), is(false));

        int tamperedIndex = randomIntBetween(InternalCryptoService.ENCRYPTED_BYTE_PREFIX.length, encrypted.length - 1);
        final byte untamperedByte = encrypted[tamperedIndex];
        byte tamperedByte = randomByte();
        while (tamperedByte == untamperedByte) {
            tamperedByte = randomByte();
        }
        encrypted[tamperedIndex] = tamperedByte;
        final byte[] decrypted = service.decrypt(encrypted);
        assertThat(Arrays.equals(bytes, decrypted), is(false));
    }

    @Test
    public void testEncryptedChar() {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));

        assertThat(service.encrypted((char[]) null), is(false));
        assertThat(service.encrypted(new char[0]), is(false));
        assertThat(service.encrypted(new char[InternalCryptoService.ENCRYPTED_TEXT_PREFIX.length()]), is(false));
        assertThat(service.encrypted(InternalCryptoService.ENCRYPTED_TEXT_PREFIX.toCharArray()), is(true));
        assertThat(service.encrypted(randomAsciiOfLengthBetween(0, 100).toCharArray()), is(false));
        assertThat(service.encrypted(service.encrypt(randomAsciiOfLength(10).toCharArray())), is(true));
    }

    @Test
    public void testEncryptedByte() {
        InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));

        assertThat(service.encrypted((byte[]) null), is(false));
        assertThat(service.encrypted(new byte[0]), is(false));
        assertThat(service.encrypted(new byte[InternalCryptoService.ENCRYPTED_BYTE_PREFIX.length]), is(false));
        assertThat(service.encrypted(InternalCryptoService.ENCRYPTED_BYTE_PREFIX), is(true));
        assertThat(service.encrypted(randomAsciiOfLengthBetween(0, 100).getBytes(Charsets.UTF_8)), is(false));
        assertThat(service.encrypted(service.encrypt(randomAsciiOfLength(10).getBytes(Charsets.UTF_8))), is(true));
    }

    @Test
    public void testReloadKey() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final CryptoService.Listener listener = new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                latch.countDown();
            }
        };

        // randomize how we set the listener
        InternalCryptoService service;
        if (randomBoolean()) {
            service = new InternalCryptoService(settings, env, watcherService, Collections.singletonList(listener)).start();
        } else {
            service = new InternalCryptoService(settings, env, watcherService).start();
            service.register(listener);
        }

        String text = randomAsciiOfLength(10);
        String signed = service.sign(text);
        char[] textChars = text.toCharArray();
        char[] encrypted = service.encrypt(textChars);

        // we need to sleep to ensure the timestamp of the file will definitely change
        // and so the resource watcher will pick up the change.
        Thread.sleep(1000L);

        try (OutputStream os = Files.newOutputStream(keyFile)) {
            Streams.copy(InternalCryptoService.generateKey(), os);
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("waiting too long for test to complete. Expected callback is not called");
        }
        String signed2 = service.sign(text);
        assertThat(signed.equals(signed2), is(false));

        char[] encrypted2 = service.encrypt(textChars);

        char[] decrypted = service.decrypt(encrypted);
        char[] decrypted2 = service.decrypt(encrypted2);
        assertThat(Arrays.equals(textChars, decrypted), is(false));
        assertThat(Arrays.equals(textChars, decrypted2), is(true));
    }

    @Test
    public void testReencryptValuesOnKeyChange() throws Exception {
        final InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));
        final char[] text = randomAsciiOfLength(10).toCharArray();
        final char[] encrypted = service.encrypt(text);
        assertThat(text, not(equalTo(encrypted)));

        final CountDownLatch latch = new CountDownLatch(1);
        service.register(new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                final char[] plainText = service.decrypt(encrypted, oldEncryptionKey);
                assertThat(plainText, equalTo(text));
                final char[] newEncrypted = service.encrypt(plainText);
                assertThat(newEncrypted, not(equalTo(encrypted)));
                assertThat(newEncrypted, not(equalTo(plainText)));
                latch.countDown();
            }
        });

        // we need to sleep to ensure the timestamp of the file will definitely change
        // and so the resource watcher will pick up the change.
        Thread.sleep(1000);

        Files.write(keyFile, InternalCryptoService.generateKey());
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("waiting too long for test to complete. Expected callback is not called or finished running");
        }
    }

    @Test
    public void testResignValuesOnKeyChange() throws Exception {
        final InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        final String text = randomAsciiOfLength(10);
        final String signed = service.sign(text);
        assertThat(text, not(equalTo(signed)));

        final CountDownLatch latch = new CountDownLatch(1);
        service.register(new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                assertThat(oldSystemKey, notNullValue());
                final String unsigned = service.unsignAndVerify(signed, oldSystemKey);
                assertThat(unsigned, equalTo(text));
                final String newSigned = service.sign(unsigned);
                assertThat(newSigned, not(equalTo(signed)));
                assertThat(newSigned, not(equalTo(text)));
                latch.countDown();
            }
        });

        // we need to sleep to ensure the timestamp of the file will definitely change
        // and so the resource watcher will pick up the change.
        Thread.sleep(1000);

        Files.write(keyFile, InternalCryptoService.generateKey());
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("waiting too long for test to complete. Expected callback is not called or finished running");
        }
    }

    @Test
    public void testReencryptValuesOnKeyDeleted() throws Exception {
        final InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));
        final char[] text = randomAsciiOfLength(10).toCharArray();
        final char[] encrypted = service.encrypt(text);
        assertThat(text, not(equalTo(encrypted)));

        final CountDownLatch latch = new CountDownLatch(1);
        service.register(new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                final char[] plainText = service.decrypt(encrypted, oldEncryptionKey);
                assertThat(plainText, equalTo(text));
                final char[] newEncrypted = service.encrypt(plainText);
                assertThat(newEncrypted, not(equalTo(encrypted)));
                assertThat(newEncrypted, equalTo(plainText));
                latch.countDown();
            }
        });

        // we need to sleep to ensure the timestamp of the file will definitely change
        // and so the resource watcher will pick up the change.
        Thread.sleep(1000);

        Files.delete(keyFile);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("waiting too long for test to complete. Expected callback is not called or finished running");
        }
    }

    @Test
    public void testAllListenersCalledWhenExceptionThrown() throws Exception {
        final InternalCryptoService service = new InternalCryptoService(settings, env, watcherService).start();
        assertThat(service.encryptionEnabled(), is(true));

        final CountDownLatch latch = new CountDownLatch(3);
        service.register(new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                latch.countDown();
            }
        });
        service.register(new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                latch.countDown();
                throw new RuntimeException("misbehaving listener");
            }
        });
        service.register(new CryptoService.Listener() {
            @Override
            public void onKeyChange(SecretKey oldSystemKey, SecretKey oldEncryptionKey) {
                latch.countDown();
            }
        });

        // we need to sleep to ensure the timestamp of the file will definitely change
        // and so the resource watcher will pick up the change.
        Thread.sleep(1000);

        Files.write(keyFile, InternalCryptoService.generateKey());
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("waiting too long for test to complete. Expected callback is not called or finished running");
        }
    }

    private static byte[] randomByteArray() {
        return randomByteArray(0);
    }

    private static byte[] randomByteArray(int min) {
        int count = randomIntBetween(min, 1000);
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = randomByte();
        }
        return bytes;
    }
}
