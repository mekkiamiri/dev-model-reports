# Contrat : stockage de pièces jointes

Paquet : `com.example.files`. Ces signatures publiques sont obligatoires ; le reste est libre.

```java
public final class AttachmentStore {
    public AttachmentStore(Path root);              // IllegalArgumentException si root est null, absent ou pas un dossier
    public void save(String name, byte[] content);  // crée la pièce ou remplace son contenu
    public byte[] read(String name);                // java.util.NoSuchElementException si la pièce n'existe pas
    public boolean delete(String name);             // true si la pièce existait (et a été supprimée), false sinon
}

public class InvalidNameException extends IllegalArgumentException {
    public InvalidNameException(String message);
}

public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause);
}
```

## Où sont les pièces

- Une pièce nommée `n` est **toujours** le fichier `racine/n`, directement sous la racine : jamais dans un sous-dossier, jamais hors de la racine.
- La racine est fixée à la construction (son chemin réel, liens résolus).

## Noms valides (exigences de sécurité)

1. Un nom valide a de 1 à 255 caractères, tous parmi `A-Z`, `a-z`, `0-9`, `.`, `_` et `-` (ASCII seulement).
2. Il ne commence pas par `.` (donc ni `.`, ni `..`, ni fichier caché).
3. Ce n'est pas un nom réservé de Windows, sans tenir compte de la casse, seul ou suivi d'une extension : `CON`, `PRN`, `AUX`, `NUL`, `COM1` à `COM9`, `LPT1` à `LPT9` (par exemple `con`, `NUL.txt`, `Com1.tar.gz`).
4. Tout autre nom (`null`, vide, avec `/` ou `\`, chemin absolu, `..`, séquence encodée comme `%2e%2e%2f`, caractère de contrôle, caractère non ASCII…) lève `InvalidNameException` **avant tout accès au disque**.

## Liens symboliques (exigence de sécurité)

5. Si `racine/n` est un lien symbolique (créé par quelqu'un d'autre), `save`, `read` et `delete` lèvent `StorageException` **sans suivre le lien** : la cible n'est ni lue, ni modifiée, ni supprimée.

## Erreurs

6. Toute autre erreur d'entrée-sortie lève `StorageException` (avec la cause).
7. `read` d'une pièce absente lève `NoSuchElementException` ; `delete` d'une pièce absente rend `false`.
8. Les messages d'erreur ne recopient pas le nom reçu tel quel (il peut contenir n'importe quoi).
