> **EXEMPLE, pas un vrai modèle : rapport généré avec un faux agent, sans appel à un modèle. Il montre le format, pas une mesure.**

# EXEMPLE : faux agent qui copie la solution de référence (pas un vrai modèle) : rapport du 2026-11-16

- Modèle : `fake-reference`
- Tâches réussies (tout ou rien, tests cachés, tous les passages) : **5/5** · passages réussis : 5/5
- Durée totale de l'agent (moyenne par tâche) : 0.1 s · coût : non fourni
- Passages par tâche : 1 · lot de tâches : public
- Version du banc : 3443389-dirty · outil : non relevée

## Résultats par tâche

| Tâche | Réussite (k/N) | Tests cachés par passage | Durée moy. | Tokens moy. | Coût | Empreinte |
|---|---|---|---|---|---|---|
| Corriger un bug de pagination signalé (bug à corriger) | réussie · 1/1 | 16/16 | 0.02 s | non fourni | non fourni | 2060fcad59b1 |
| Configuration en couches avec variables (PRD vers code) | réussie · 1/1 | 32/32 | 0.03 s | non fourni | non fourni | 2bfda8f9d336 |
| Calcul de facture à partir d'un CSV (PRD vers code) | réussie · 1/1 | 25/25 | 0.03 s | non fourni | non fourni | 7caf472ec6f9 |
| Horaires d'ouverture d'une boutique (PRD vers code) | réussie · 1/1 | 41/41 | 0.03 s | non fourni | non fourni | f0a3181968ce |
| Refactorer un calcul de prix en règles séparées (refactoring) | réussie · 1/1 | 18/18 | 0.03 s | non fourni | non fourni | 768687ae59f1 |

## Ressenti (grille v1, voir RESSENTI.md)

| Critère | Note | Preuve |
|---|---|---|
| Lisibilité | non noté | — |
| Respect des conventions | non noté | — |
| Respect du périmètre | non noté | — |
| Questions et hypothèses à bon escient | non noté | — |
| Honnêteté sur ce qui n'est pas fait | non noté | — |
| Vérification par soi-même | non noté | — |
| Économie de moyens | non noté | — |

## Méthode et limites

Protocole complet : `PROTOCOLE.md`. Tâches neuves écrites pour ce banc, tests d'acceptation cachés, aucun juge LLM pour la réussite. Deux rapports ne se comparent sur une tâche que si l'empreinte est identique. Un seul notateur pour le ressenti. Peu de passages par tâche : un écart d'une tâche entre deux modèles n'est pas significatif.
