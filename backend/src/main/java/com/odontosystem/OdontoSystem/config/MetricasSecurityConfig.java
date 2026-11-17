package com.odontosystem.OdontoSystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Monitoreo (S16): /actuator/prometheus lo lee Grafana Cloud. Expone detalles internos (rutas, tiempos, memoria),
 * así que tiene su propia cadena de seguridad, evaluada antes que la de la API:
 * <ul>
 *   <li>HTTP Basic con un usuario propio (METRICAS_USUARIO / METRICAS_PASSWORD), no con las cuentas de la app:
 *       el AuthenticationManager es solo de esta cadena, sin el proveedor de usuarios de la base.</li>
 *   <li>Si no hay contraseña (o tiene menos de 16 caracteres), el endpoint queda cerrado para todos.</li>
 * </ul>
 */
@Configuration
public class MetricasSecurityConfig {

    static final String RUTA = "/actuator/prometheus";
    static final int LARGO_MINIMO = 16;

    @Bean
    @Order(1)
    public SecurityFilterChain cadenaMetricas(HttpSecurity http,
                                              @Value("${app.metricas.usuario:grafana}") String usuario,
                                              @Value("${app.metricas.password:}") String password) throws Exception {
        http.securityMatcher(RUTA)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (usuario == null || usuario.isBlank() || password == null || password.length() < LARGO_MINIMO) {
            http.httpBasic(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(a -> a.anyRequest().denyAll());
            return http.build();
        }

        BCryptPasswordEncoder codificador = new BCryptPasswordEncoder();
        InMemoryUserDetailsManager usuarios = new InMemoryUserDetailsManager(
                User.withUsername(usuario).password(codificador.encode(password)).roles("METRICAS").build());
        DaoAuthenticationProvider proveedor = new DaoAuthenticationProvider(usuarios);
        proveedor.setPasswordEncoder(codificador);

        http.authenticationManager(new ProviderManager(proveedor))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("METRICAS"));
        return http.build();
    }
}
