package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;

@Entity
@Table(name = "User_accounts", schema = "identity_service")
@Getter
public class UserAccount {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(nullable = false, length = 100, updatable = false)
  private String fullname;

  @Column(nullable = false, length = 254, updatable = false)
  private String email;

  @Getter(AccessLevel.NONE)
  @Column(name = "password_hash", nullable = false, columnDefinition = "text")
  private String passwordHash;

  protected UserAccount() {}

  public UserAccount(String fullName, String email, String encodedPassword) {
    this.fullname = fullName;
    this.email = email;
    this.passwordHash = encodedPassword;
  }
}
