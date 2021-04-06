/*******************************************************************************
 * Copyright (c) 2011, 2021 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.core.ssl;

import static java.util.Objects.isNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.TrustedCertificateEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraRuntimeException;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.security.keystore.KeystoreService;
import org.eclipse.kura.ssl.SslManagerService;
import org.eclipse.kura.ssl.SslServiceListener;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SslManagerServiceImpl implements SslManagerService, ConfigurableComponent {

    private static final Logger logger = LoggerFactory.getLogger(SslManagerServiceImpl.class);

    private SslServiceListeners sslServiceListeners;

    private SslManagerServiceOptions options;

    private KeystoreService keystoreService;

    private Map<ConnectionSslOptions, SSLContext> sslContexts;

    // ----------------------------------------------------------------
    //
    // Dependencies
    //
    // ----------------------------------------------------------------

    public void setKeystoreService(KeystoreService keystoreService) {
        this.keystoreService = keystoreService;

        if (this.sslServiceListeners != null) {
            // Notify listeners that service has been updated
            this.sslServiceListeners.onConfigurationUpdated();
        }
    }

    public void unsetKeystoreService(KeystoreService keystoreService) {
        if (this.keystoreService == keystoreService) {
            this.keystoreService = null;
        }
    }

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        logger.info("activate...");

        this.options = new SslManagerServiceOptions(properties);
        this.sslContexts = new ConcurrentHashMap<>();

        ServiceTracker<SslServiceListener, SslServiceListener> listenersTracker = new ServiceTracker<>(
                componentContext.getBundleContext(), SslServiceListener.class, null);

        // Deferred open of tracker to prevent
        // java.lang.Exception: Recursive invocation of
        // ServiceFactory.getService
        // on ProSyst
        this.sslServiceListeners = new SslServiceListeners(listenersTracker);
    }

    public void updated(Map<String, Object> properties) {
        logger.info("updated...");

        this.options = new SslManagerServiceOptions(properties);
        this.sslContexts = new ConcurrentHashMap<>();

        // Notify listeners that service has been updated
        this.sslServiceListeners.onConfigurationUpdated();
    }

    protected void deactivate(ComponentContext componentContext) {
        logger.info("deactivate...");
        this.sslServiceListeners.close();
    }

    // ----------------------------------------------------------------
    //
    // Service APIs
    //
    // ----------------------------------------------------------------

    @Override
    public SSLContext getSSLContext() throws GeneralSecurityException, IOException {
        return getSSLContext("");
    }

    @Override
    public SSLContext getSSLContext(String keyAlias) throws GeneralSecurityException, IOException {
        String protocol = this.options.getSslProtocol();
        String ciphers = this.options.getSslCiphers();
        boolean hostnameVerifcation = this.options.isSslHostnameVerification();

        return getSSLContext(protocol, ciphers, null, null, null, keyAlias, hostnameVerifcation);
    }

    @Override
    public SSLContext getSSLContext(String protocol, String ciphers, String trustStore, String keyStore,
            char[] keyStorePassword, String keyAlias) throws GeneralSecurityException, IOException {
        return getSSLContext(protocol, ciphers, trustStore, keyStore, keyStorePassword, keyAlias,
                this.options.isSslHostnameVerification());
    }

    @Override
    public SSLContext getSSLContext(String protocol, String ciphers, String trustStore, String keyStore,
            char[] keyStorePassword, String keyAlias, boolean hostnameVerification)
            throws GeneralSecurityException, IOException {
        ConnectionSslOptions connSslOpts = new ConnectionSslOptions(this.options);
        connSslOpts.setProtocol(protocol);
        connSslOpts.setCiphers(ciphers);
        connSslOpts.setTrustStore(trustStore);
        connSslOpts.setKeyStore(keyStore);
        connSslOpts.setKeyStorePassword(keyStorePassword);
        connSslOpts.setAlias(keyAlias);
        connSslOpts.setHostnameVerification(hostnameVerification);

        return getSSLContextInternal(connSslOpts);
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory() throws GeneralSecurityException, IOException {
        return getSSLContext().getSocketFactory();
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory(String keyAlias) throws GeneralSecurityException, IOException {
        return getSSLContext(keyAlias).getSocketFactory();
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory(String protocol, String ciphers, String trustStore, String keyStore,
            char[] keyStorePassword, String keyAlias) throws GeneralSecurityException, IOException {
        return getSSLContext(protocol, ciphers, trustStore, keyStore, keyStorePassword, keyAlias).getSocketFactory();
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory(String protocol, String ciphers, String trustStore, String keyStore,
            char[] keyStorePassword, String keyAlias, boolean hostnameVerification)
            throws GeneralSecurityException, IOException {
        return getSSLContext(protocol, ciphers, trustStore, keyStore, keyStorePassword, keyAlias, hostnameVerification)
                .getSocketFactory();
    }

    @Override
    public X509Certificate[] getTrustCertificates() throws GeneralSecurityException, IOException {
        X509Certificate[] cacerts = null;
        TrustManager[] tms = getTrustManagers();
        for (TrustManager tm : tms) {
            if (tm instanceof X509TrustManager) {
                X509TrustManager x509tm = (X509TrustManager) tm;
                cacerts = x509tm.getAcceptedIssuers();
                break;
            }
        }
        return cacerts;
    }

    @Override
    public void installTrustCertificate(String alias, X509Certificate x509crt)
            throws GeneralSecurityException, IOException {

        if (isNull(this.keystoreService)) {
            throw new KuraRuntimeException(KuraErrorCode.INTERNAL_ERROR); // TO DO:review
        }

        TrustedCertificateEntry trustedCertificateEntry = new TrustedCertificateEntry(x509crt);
        this.keystoreService.setEntry(alias, trustedCertificateEntry);
    }

    @Override
    public void deleteTrustCertificate(String alias) throws GeneralSecurityException, IOException {
        this.keystoreService.deleteEntry(alias);
    }

    @Override
    public void installPrivateKey(String alias, PrivateKey privateKey, char[] password, Certificate[] publicCerts)
            throws GeneralSecurityException, IOException {
        if (isNull(this.keystoreService)) {
            throw new KuraRuntimeException(KuraErrorCode.INTERNAL_ERROR); // TO DO:review
        }

        PrivateKeyEntry privateKeyEntry = new PrivateKeyEntry(privateKey, publicCerts);

        this.keystoreService.setEntry(alias, privateKeyEntry);
    }

    private KeyStore loadKeystore(String keyStore, char[] keyStorePassword)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

        try (InputStream tsReadStream = new FileInputStream(keyStore);) {
            ks.load(tsReadStream, keyStorePassword);
        }

        return ks;
    }

    // ----------------------------------------------------------------
    //
    // Private methods
    //
    // ----------------------------------------------------------------

    private SSLContext getSSLContextInternal(ConnectionSslOptions options)
            throws GeneralSecurityException, IOException {
        // Only create a new SSLSocketFactory instance if the configuration has
        // changed or
        // for a new alias.
        // This allows for SSL Context Resumption and abbreviated SSL handshake
        // in case of reconnects to the same host.
        SSLContext context = this.sslContexts.get(options);
        if (context == null) {
            logger.info("Creating a new SSLSocketFactory instance");

            TrustManager[] tms = null;
            KeyManager[] kms = null;
            if (isNull(options.getTrustStore()) && isNull(options.getKeyStorePassword())) {
                tms = getTrustManagers();
                kms = getKeyManagers();
            } else {
                tms = getTrustManagers(options.getTrustStore(), options.getKeyStorePassword());
                kms = getKeyManagers(options.getKeyStore(), options.getKeyStorePassword(), options.getAlias());
            }

            context = createSSLContext(options.getProtocol(), options.getCiphers(), kms, tms,
                    options.getHostnameVerification());
            this.sslContexts.put(options, context);
        }

        return context;
    }

    private static SSLContext createSSLContext(String protocol, String ciphers, KeyManager[] kms, TrustManager[] tms,
            boolean hostnameVerification) throws NoSuchAlgorithmException, KeyManagementException {
        // inits the SSL context
        SSLContext sslCtx;
        if (protocol == null || protocol.isEmpty()) {
            sslCtx = SSLContext.getDefault();
        } else {
            sslCtx = SSLContext.getInstance(protocol);
            sslCtx.init(kms, tms, null);
        }

        // get the SSLSocketFactory
        final SSLSocketFactory sslSocketFactory = sslCtx.getSocketFactory();
        final SSLSocketFactoryWrapper socketFactoryWrapper = new SSLSocketFactoryWrapper(sslSocketFactory, ciphers,
                hostnameVerification);

        // wrap it
        return new SSLContext(new SSLContextSPIWrapper(sslCtx, socketFactoryWrapper), sslCtx.getProvider(),
                sslCtx.getProtocol()) {
        };
    }

    private TrustManager[] getTrustManagers() throws GeneralSecurityException, IOException {
        TrustManager[] result = new TrustManager[0];
        TrustManagerFactory tmf = null;
        if (this.keystoreService != null) {
            KeyStore ts = this.keystoreService.getKeyStore();
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            result = tmf.getTrustManagers();
        }
        return result;
    }

    private TrustManager[] getTrustManagers(String trustStore, char[] keyStorePassword)
            throws IOException, GeneralSecurityException {
        TrustManager[] result = new TrustManager[0];
        TrustManagerFactory tmf = null;
        if (trustStore != null) {

            // Load the configured the Trust Store
            File fTrustStore = new File(trustStore);
            if (fTrustStore.exists()) {

                KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
                InputStream tsReadStream = new FileInputStream(trustStore);
                ts.load(tsReadStream, keyStorePassword);
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                result = tmf.getTrustManagers();
                tsReadStream.close();
            }
        }
        return result;
    }

    private KeyManager[] getKeyManagers() throws GeneralSecurityException, IOException {
        if (isNull(this.keystoreService)) {
            throw new KuraRuntimeException(KuraErrorCode.INTERNAL_ERROR); // TO DO:review
        }
        return this.keystoreService.getKeyManagers(KeyManagerFactory.getDefaultAlgorithm()).toArray(new KeyManager[0]); // TO DO:
                                                                                                                      // it
                                                                                                                      // was
                                                                                                                      // possible
                                                                                                                      // to
                                                                                                                      // load
                                                                                                                      // a
                                                                                                                      // keystore
                                                                                                                      // based
                                                                                                                      // on
                                                                                                                      // Alias.
                                                                                                                      // Now
                                                                                                                      // it
                                                                                                                      // is
                                                                                                                   // not
                                                                                                                      // possible.
    }

    private KeyManager[] getKeyManagers(String keyStore, char[] keyStorePassword, String keyAlias)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException,
            UnrecoverableEntryException {
        KeyStore ks = getKeyStore(keyStore, keyStorePassword, keyAlias);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePassword);

        return kmf.getKeyManagers();
    }

    private KeyStore getKeyStore(String keyStore, char[] keyStorePassword, String keyAlias) throws KeyStoreException,
            IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableEntryException {

        // Load the configured the Key Store
        File fKeyStore = new File(keyStore);
        if (!fKeyStore.exists() || !isKeyStoreAccessible(keyStore, keyStorePassword)) {
            logger.warn("The referenced keystore does not exist or is not accessible");
            throw new KeyStoreException("The referenced keystore does not exist or is not accessible");
        }

        try (InputStream ksReadStream = new FileInputStream(keyStore);) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(ksReadStream, keyStorePassword);

            // if we have an alias, then build KeyStore with such key
            if (ks.containsAlias(keyAlias) && ks.isKeyEntry(keyAlias)) {
                PasswordProtection pp = new PasswordProtection(keyStorePassword);
                Entry entry = ks.getEntry(keyAlias, pp);
                ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setEntry(keyAlias, entry, pp);
            }

            return ks;
        }
    }

    private boolean isKeyStoreAccessible(String location, char[] password) {
        try {
            loadKeystore(location, password);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
