//==============================================================================
// This file is part of Master Password.
// Copyright (c) 2011-2017, Maarten Billemont.
//
// Master Password is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// Master Password is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You can find a copy of the GNU General Public License in the
// LICENSE file.  Alternatively, see <http://www.gnu.org/licenses/>.
//==============================================================================

package com.lyndir.masterpassword;

import static com.lyndir.masterpassword.MPUtils.*;

import com.google.common.base.*;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedInteger;
import com.lambdaworks.crypto.SCrypt;
import com.lyndir.lhunath.opal.crypto.CryptUtils;
import com.lyndir.lhunath.opal.system.*;
import com.lyndir.lhunath.opal.system.logging.Logger;
import com.lyndir.lhunath.opal.system.util.ConversionUtils;
import java.nio.*;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;


/**
 * @author lhunath, 2014-08-30
 * @see MPMasterKey.Version#V0
 */
public class MPAlgorithmV0 implements MPAlgorithm {

    protected final Logger logger = Logger.get( getClass() );

    @Override
    public MPMasterKey.Version getAlgorithmVersion() {

        return MPMasterKey.Version.V0;
    }

    @Override
    public byte[] masterKey(final String fullName, final char[] masterPassword) {

        byte[] fullNameBytes       = fullName.getBytes( mpw_charset );
        byte[] fullNameLengthBytes = bytesForInt( fullName.length() );

        String keyScope = MPKeyPurpose.Authentication.getScope();
        logger.trc( "keyScope: %s", keyScope );

        // Calculate the master key salt.
        logger.trc( "masterKeySalt: keyScope=%s | #fullName=%s | fullName=%s",
                    keyScope, CodeUtils.encodeHex( fullNameLengthBytes ), fullName );
        byte[] masterKeySalt = Bytes.concat( keyScope.getBytes( mpw_charset ), fullNameLengthBytes, fullNameBytes );
        logger.trc( "  => masterKeySalt.id: %s", CodeUtils.encodeHex( idForBytes( masterKeySalt ) ) );

        // Calculate the master key.
        logger.trc( "masterKey: scrypt( masterPassword, masterKeySalt, N=%d, r=%d, p=%d )",
                    scrypt_N, scrypt_r, scrypt_p );
        byte[] masterPasswordBytes = bytesForChars( masterPassword );
        byte[] masterKey           = scrypt( masterKeySalt, masterPasswordBytes );
        Arrays.fill( masterKeySalt, (byte) 0 );
        Arrays.fill( masterPasswordBytes, (byte) 0 );
        logger.trc( "  => masterKey.id: %s", CodeUtils.encodeHex( idForBytes( masterKey ) ) );

        return masterKey;
    }

    protected byte[] scrypt(final byte[] masterKeySalt, final byte[] mpBytes) {
        try {
            //if (isAllowNative())
            return SCrypt.scrypt( mpBytes, masterKeySalt, scrypt_N, scrypt_r, scrypt_p, mpw_dkLen );
            //else
            //    return SCrypt.scryptJ( mpBytes, masterKeySalt, scrypt_N, scrypt_r, scrypt_p, mpw_dkLen );
        }
        catch (final GeneralSecurityException e) {
            throw logger.bug( e );
        }
    }

    @Override
    public byte[] siteKey(final byte[] masterKey, final String siteName, UnsignedInteger siteCounter, final MPKeyPurpose keyPurpose,
                          @Nullable final String keyContext) {

        String keyScope = keyPurpose.getScope();
        logger.trc( "keyScope: %s", keyScope );

        // OTP counter value.
        if (siteCounter.longValue() == 0)
            siteCounter = UnsignedInteger.valueOf( (System.currentTimeMillis() / (mpw_otp_window * 1000)) * mpw_otp_window );

        // Calculate the site seed.
        byte[] siteNameBytes         = siteName.getBytes( mpw_charset );
        byte[] siteNameLengthBytes   = bytesForInt( siteName.length() );
        byte[] siteCounterBytes      = bytesForInt( siteCounter );
        byte[] keyContextBytes       = ((keyContext == null) || keyContext.isEmpty())? null: keyContext.getBytes( mpw_charset );
        byte[] keyContextLengthBytes = (keyContextBytes == null)? null: bytesForInt( keyContextBytes.length );
        logger.trc( "siteSalt: keyScope=%s | #siteName=%s | siteName=%s | siteCounter=%s | #keyContext=%s | keyContext=%s",
                    keyScope, CodeUtils.encodeHex( siteNameLengthBytes ), siteName, CodeUtils.encodeHex( siteCounterBytes ),
                    (keyContextLengthBytes == null)? null: CodeUtils.encodeHex( keyContextLengthBytes ), keyContext );

        byte[] sitePasswordInfo = Bytes.concat( keyScope.getBytes( mpw_charset ), siteNameLengthBytes, siteNameBytes, siteCounterBytes );
        if (keyContextBytes != null)
            sitePasswordInfo = Bytes.concat( sitePasswordInfo, keyContextLengthBytes, keyContextBytes );
        logger.trc( "  => siteSalt.id: %s", CodeUtils.encodeHex( idForBytes( sitePasswordInfo ) ) );

        logger.trc( "siteKey: hmac-sha256( masterKey.id=%s, siteSalt )", CodeUtils.encodeHex( idForBytes( masterKey ) ) );
        byte[] sitePasswordSeedBytes = mpw_digest.of( masterKey, sitePasswordInfo );
        logger.trc( "  => siteKey.id: %s", CodeUtils.encodeHex( idForBytes( sitePasswordSeedBytes ) ) );

        return sitePasswordSeedBytes;
    }

    @Override
    public String siteResult(final byte[] masterKey, final byte[] siteKey, final String siteName, final UnsignedInteger siteCounter,
                             final MPKeyPurpose keyPurpose,
                             @Nullable final String keyContext, final MPResultType resultType, @Nullable final String resultParam) {

        switch (resultType.getTypeClass()) {
            case Template:
                return sitePasswordFromTemplate( masterKey, siteKey, resultType, resultParam );
            case Stateful:
                return sitePasswordFromCrypt( masterKey, siteKey, resultType, resultParam );
            case Derive:
                return sitePasswordFromDerive( masterKey, siteKey, resultType, resultParam );
        }

        throw logger.bug( "Unsupported result type class: %s", resultType.getTypeClass() );
    }

    @Override
    public String sitePasswordFromTemplate(final byte[] masterKey, final byte[] siteKey, final MPResultType resultType,
                                           @Nullable final String resultParam) {

        int[] _siteKey = new int[siteKey.length];
        for (int i = 0; i < siteKey.length; ++i) {
            ByteBuffer buf = ByteBuffer.allocate( Integer.SIZE / Byte.SIZE ).order( mpw_byteOrder );
            Arrays.fill( buf.array(), (byte) ((siteKey[i] > 0)? 0x00: 0xFF) );
            buf.position( 2 );
            buf.put( siteKey[i] ).rewind();
            _siteKey[i] = buf.getInt() & 0xFFFF;
        }

        // Determine the template.
        Preconditions.checkState( _siteKey.length > 0 );
        int        templateIndex = _siteKey[0];
        MPTemplate template      = resultType.getTemplateAtRollingIndex( templateIndex );
        logger.trc( "template: %d => %s", templateIndex, template.getTemplateString() );

        // Encode the password from the seed using the template.
        StringBuilder password = new StringBuilder( template.length() );
        for (int i = 0; i < template.length(); ++i) {
            int                      characterIndex    = _siteKey[i + 1];
            MPTemplateCharacterClass characterClass    = template.getCharacterClassAtIndex( i );
            char                     passwordCharacter = characterClass.getCharacterAtRollingIndex( characterIndex );
            logger.trc( "  - class: %c, index: %5d (0x%2H) => character: %c",
                        characterClass.getIdentifier(), characterIndex, _siteKey[i + 1], passwordCharacter );

            password.append( passwordCharacter );
        }
        logger.trc( "  => password: %s", password );

        return password.toString();
    }

    @Override
    public String sitePasswordFromCrypt(final byte[] masterKey, final byte[] siteKey, final MPResultType resultType,
                                        @Nullable final String resultParam) {

        Preconditions.checkNotNull( resultParam );
        Preconditions.checkArgument( !resultParam.isEmpty() );

        try {
            // Base64-decode
            byte[] cipherBuf = CryptUtils.decodeBase64( resultParam );
            logger.trc( "b64 decoded: %d bytes = %s", cipherBuf.length, CodeUtils.encodeHex( cipherBuf ) );

            // Decrypt
            byte[] plainBuf  = CryptUtils.decrypt( cipherBuf, masterKey, true );
            String plainText = mpw_charset.decode( ByteBuffer.wrap( plainBuf ) ).toString();
            logger.trc( "decrypted -> plainText: %d bytes = %s = %s", plainBuf.length, plainText, CodeUtils.encodeHex( plainBuf ) );

            return plainText;
        }
        catch (final BadPaddingException e) {
            throw Throwables.propagate( e );
        }
    }

    @Override
    public String sitePasswordFromDerive(final byte[] masterKey, final byte[] siteKey, final MPResultType resultType,
                                         @Nullable final String resultParam) {

        if (resultType == MPResultType.DeriveKey) {
            int resultParamInt = ConversionUtils.toIntegerNN( resultParam );
            if (resultParamInt == 0)
                resultParamInt = 512;
            if ((resultParamInt < 128) || (resultParamInt > 512) || ((resultParamInt % 8) != 0))
                throw logger.bug( "Parameter is not a valid key size (should be 128 - 512): %s", resultParam );
            int keySize = resultParamInt / 8;
            logger.trc( "keySize: %d", keySize );

            // Derive key
            byte[] resultKey = null; // TODO: mpw_kdf_blake2b( keySize, siteKey, MPSiteKeySize, NULL, 0, 0, NULL );
            if (resultKey == null)
                throw logger.bug( "Could not derive result key." );

            // Base64-encode
            String b64Key = Verify.verifyNotNull( CryptUtils.encodeBase64( resultKey ) );
            logger.trc( "b64 encoded -> key: %s", b64Key );

            return b64Key;
        } else
            throw logger.bug( "Unsupported derived password type: %s", resultType );
    }

    @Override
    public String siteState(final byte[] masterKey, final byte[] siteKey, final String siteName, final UnsignedInteger siteCounter,
                            final MPKeyPurpose keyPurpose,
                            @Nullable final String keyContext, final MPResultType resultType, final String resultParam) {

        try {
            // Encrypt
            byte[] cipherBuf = CryptUtils.encrypt( resultParam.getBytes( mpw_charset ), masterKey, true );
            logger.trc( "cipherBuf: %d bytes = %s", cipherBuf.length, CodeUtils.encodeHex( cipherBuf ) );

            // Base64-encode
            String cipherText = Verify.verifyNotNull( CryptUtils.encodeBase64( cipherBuf ) );
            logger.trc( "b64 encoded -> cipherText: %s", cipherText );

            return cipherText;
        }
        catch (final IllegalBlockSizeException e) {
            throw logger.bug( e );
        }
    }
}
