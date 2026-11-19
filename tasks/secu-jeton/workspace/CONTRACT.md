# Contrat : jetons signés HMAC-SHA256

Paquet : `com.example.token`. Ces signatures publiques sont obligatoires ; le reste est libre.

```java
public final class TokenService {
    public TokenService(byte[] key, Clock clock);      // IllegalArgumentException si key ou clock est null, ou si key fait moins de 32 octets
    public String sign(String subject, Duration ttl);  // IllegalArgumentException si subject ou ttl est invalide
    public String verify(String token);                // rend le sujet ; InvalidTokenException si le jeton n'est pas valide
}

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message);
}
```

HMAC avec `javax.crypto.Mac` (`HmacSHA256`), bibliothèque standard uniquement.

## Format du jeton

`HS256.<sujet>.<exp>.<signature>`, soit quatre parties séparées par `.` :

- `HS256` : l'algorithme, exactement ce texte ;
- `<sujet>` : de 1 à 64 caractères parmi `A-Z`, `a-z`, `0-9`, `_` et `-` ;
- `<exp>` : l'instant d'expiration, en secondes depuis l'époque Unix, écrit en chiffres décimaux ASCII, sans signe ni zéro en tête, 18 chiffres au plus ;
- `<signature>` : le HMAC-SHA256, avec la clé, des octets ASCII de `HS256.<sujet>.<exp>`, encodé en Base64 URL **sans** `=` final (`Base64.getUrlEncoder().withoutPadding()`, 43 caractères).

## sign

- `subject` doit respecter le format du sujet, sinon `IllegalArgumentException`.
- `ttl` est tronqué à la seconde et doit valoir au moins une seconde, sinon `IllegalArgumentException`.
- `exp` = secondes de `clock.instant()` + secondes de `ttl`.

## verify (exigences de sécurité)

Rend le sujet si, et seulement si, toutes ces conditions sont vraies ; sinon lève `InvalidTokenException`, jamais une autre exception :

1. le jeton a exactement quatre parties et l'algorithme vaut exactement `HS256` (tout autre texte, dont `none`, `hs256` ou `HS512`, est refusé, signature ou pas) ;
2. le sujet et `exp` respectent le format ci-dessus ;
3. la signature fait exactement 43 caractères Base64 URL (une signature vide, tronquée, rallongée ou avec `=` est refusée) et correspond au HMAC recalculé ;
4. la comparaison des signatures se fait en temps constant avec `java.security.MessageDigest.isEqual` (pas de `equals`, de `Arrays.equals` ni de boucle qui s'arrête au premier écart) ;
5. le jeton n'est pas expiré : il est refusé dès que les secondes de `clock.instant()` sont supérieures ou égales à `exp`.

## Clé

6. La clé fait au moins 32 octets (256 bits) ; une clé plus courte est refusée à la construction.
