package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAccountWriter {
  private final UserAccountRepository userAccountRepository;

  @Transactional
  public CreatedUser create(String fullName, String canonicalEmail, String encodedPassword) {
    var account =
        userAccountRepository.saveAndFlush(
            new UserAccount(fullName, canonicalEmail, encodedPassword));
    return new CreatedUser(account.getId(), account.getFullname(), account.getEmail());
  }
}
