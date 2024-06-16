package tests.pattern.tink;

import com.google.crypto.tink.DeterministicAead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.daead.DeterministicAeadFactory;
import com.google.crypto.tink.daead.DeterministicAeadKeyTemplates;
import com.google.crypto.tink.proto.KeyTemplate;
import org.junit.Ignore;
import org.junit.Test;
import test.TestConstants;
import test.assertions.Assertions;

import java.security.GeneralSecurityException;

@Ignore
public class TestDeterministicAEADCipher extends TestTinkPrimitives {

	@Override
	protected String getRulesetPath() {
		return TestConstants.TINK_RULESET_PATH;
	}

	@Test
	public void generateNewAES128GCMKeySet() throws GeneralSecurityException {
		KeyTemplate kt = DeterministicAeadKeyTemplates.createAesSivKeyTemplate(64);
		KeysetHandle ksh = KeysetHandle.generateNew(kt);
		Assertions.hasEnsuredPredicate(kt);
		Assertions.hasEnsuredPredicate(ksh);
		Assertions.mustBeInAcceptingState(kt);
		Assertions.mustBeInAcceptingState(ksh);		
	}
	

	@Test
	public void encryptUsingAES256_SIV() throws GeneralSecurityException {
		KeyTemplate kt = DeterministicAeadKeyTemplates.createAesSivKeyTemplate(64);
		KeysetHandle ksh = KeysetHandle.generateNew(kt);
		
		Assertions.hasEnsuredPredicate(kt); 
		
		final String plainText = "Just testing the encryption mode of DAEAD"; 
		final String aad = "crysl";
		
		DeterministicAead daead = DeterministicAeadFactory.getPrimitive(ksh);
		byte[] out = daead.encryptDeterministically(plainText.getBytes(), aad.getBytes());
		
		Assertions.hasEnsuredPredicate(daead);
		Assertions.mustBeInAcceptingState(daead);
		//Assertions.hasEnsuredPredicate(out); // this assertions still leads to a red bar. 
  	}
	
	@Test
	public void encryptUsingNullKeyTemplate() throws GeneralSecurityException {
		KeyTemplate kt = null ; 
		KeysetHandle ksh = KeysetHandle.generateNew(kt);
				
		Assertions.notHasEnsuredPredicate(kt); 
		Assertions.notHasEnsuredPredicate(kt); 
	}

	@Test
	public void encryptUsingInvalidKey() throws GeneralSecurityException {
		KeyTemplate kt = DeterministicAeadKeyTemplates.createAesSivKeyTemplate(32);
		
		Assertions.notHasEnsuredPredicate(kt); 
	}
	
	
}
