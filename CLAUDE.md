# Reglas de trabajo para DOZZER
- El proyecto se divide en dos repositorios dentro de esta carpeta: Dozzer_Frontend (Angular) y Dozzer_Backend (Spring Boot / Java 21).
- SIEMPRE trabaja y sube los cambios directamente a la rama feature/versionAlServidor en ambos repositorios.
- El usuario revisa los cambios directamente en producción (https://dozzer.pages.dev y https://dozzer-api.onrender.com). No le pidas que abra localhost.
- Antes de editar, haz git pull en el repositorio correspondiente para no chocar con cambios recientes.
- Al terminar cualquier cambio solicitado, verifica que compile (npm run build en Frontend o ./mvnw test-compile en Backend, con JAVA_HOME apuntando a un JDK 21), limpia cualquier archivo compilado temporal (como target/) y ejecuta automáticamente git add, git commit -m "..." y git push origin feature/versionAlServidor.
- Si un git push es rechazado porque hay cambios remotos nuevos, ejecuta automáticamente git pull --rebase origin feature/versionAlServidor y vuelve a hacer git push. Si el rebase tiene conflictos, detente y avisa al usuario en lugar de resolverlos a ciegas.
- NUNCA subas ni modifiques el archivo secretos.properties.
- Las reglas se editan SOLO en C:\PROYECTOS\DOZZER\CLAUDE.md. Un hook (.claude/hooks/sync-claude-md.js) lo copia automáticamente a Dozzer_Backend/CLAUDE.md y Dozzer_Frontend/CLAUDE.md; después de cada cambio, haz commit y push de esa copia en ambos repositorios. No edites las copias de los repositorios directamente.