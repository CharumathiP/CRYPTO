package pkc.ecc.insecurecurves;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.ECGenParameterSpec;

public final class InsecureCurve_secp160r1 {

	/**
	 * Original test with updated constraints:
	 * 	new ECGenParameterSpec("secp160r1") -> new ECGenParameterSpec("secp521r1")
	 * 
	 * This test does not contain any errors
	 */
	public void positiveTestCase() {
		try {
			ECGenParameterSpec ecps = new ECGenParameterSpec("secp521r1");

			KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "SunEC");
			kpg.initialize(ecps);
			KeyPair kp = kpg.generateKeyPair();
		} catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchProviderException e) {
		}
	}

	/**
	 * Original test without updates
	 */
	public void negativeTestCase() {
		try {
			// Since 3.0.0: secp112r1 is not a secure curve
			ECGenParameterSpec ecps = new ECGenParameterSpec("secp160r1");

			KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "SunEC");
			kpg.initialize(ecps);
			KeyPair kp = kpg.generateKeyPair();
		} catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchProviderException e) {
		}
	}
}
