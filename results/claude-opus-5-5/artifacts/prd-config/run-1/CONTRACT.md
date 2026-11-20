# Contrat d'interface

Paquet : `com.example.config`. Ces signatures publiques sont obligatoires ; le reste est libre.

```java
public final class ConfigResolver {
    /** Couches de la moins prioritaire à la plus prioritaire ; résultat : clé -> valeur résolue. */
    public static Map<String, String> resolve(List<String> layers);   // lève ConfigException
}

public class ConfigException extends RuntimeException {
    public ConfigException(String message);
}
```

La map rendue peut être de n'importe quel type (`HashMap`, `TreeMap`…) ; seul son contenu compte. Les fins de ligne peuvent être `\n` ou `\r\n`.
