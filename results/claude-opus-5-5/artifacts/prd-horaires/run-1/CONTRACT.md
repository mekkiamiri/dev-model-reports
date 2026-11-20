# Contrat d'interface

Paquet : `com.example.hours`. Ces signatures publiques sont obligatoires ; le reste est libre.

```java
public final class OpeningHours {
    public static OpeningHours parse(String spec);           // IllegalArgumentException si invalide
    public boolean isOpen(LocalDateTime t);
    public Optional<LocalDateTime> nextOpening(LocalDateTime t);
}
```

Types de `java.time` et `java.util.Optional`. Pas de fuseau horaire : tout est en heure locale.
