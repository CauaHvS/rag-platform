package dev.ragplatform.infrastructure.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * DataSource wrapper que propaga o RlsContext para o Postgres via set_config().
 *
 * A cada aquisição lógica de conexão (getConnection), executa:
 *   SELECT set_config('app.current_user_id', ?, false)
 *
 * is_local=false: persiste na sessão da conexão (não só na transação atual).
 * Como o wrapper é chamado em TODA aquisição de conexão do pool, o valor é
 * sempre sobrescrito com o userId do request corrente (ou string vazia para
 * contextos sem usuário), nunca herdando estado de uma requisição anterior.
 *
 * O RLS no Postgres interpreta:
 *   - string vazia ou ausência da variável → bypass (background jobs, Flyway)
 *   - UUID string → filtro owner_id = UUID
 */
public class RlsDataSourceWrapper extends DelegatingDataSource {

    public RlsDataSourceWrapper(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = super.getConnection();
        applyContext(conn);
        return conn;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = super.getConnection(username, password);
        applyContext(conn);
        return conn;
    }

    private void applyContext(Connection conn) throws SQLException {
        String userId = RlsContext.get();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('app.current_user_id', ?, false)")) {
            ps.setString(1, userId != null ? userId : "");
            ps.execute();
        }
    }
}
