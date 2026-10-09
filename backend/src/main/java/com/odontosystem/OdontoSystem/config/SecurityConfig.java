package com.odontosystem.OdontoSystem.config;

import com.odontosystem.OdontoSystem.security.JwtAuthenticationFilter;
import com.odontosystem.OdontoSystem.security.RespuestasSeguridad;
import com.odontosystem.OdontoSystem.security.UsuarioDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final UsuarioDetailsService usuarioDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RespuestasSeguridad respuestasSeguridad;

    public SecurityConfig(UsuarioDetailsService usuarioDetailsService, JwtAuthenticationFilter jwtAuthenticationFilter,
                          RespuestasSeguridad respuestasSeguridad) {
        this.usuarioDetailsService = usuarioDetailsService;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.respuestasSeguridad = respuestasSeguridad;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(usuarioDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. Desactiva la página de login HTML por defecto
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 2. Desactiva CSRF (necesario para APIs REST que usarán JWT)
                .csrf(AbstractHttpConfigurer::disable)
                // 3. Configura las sesiones como sin estado (Stateless) para JWT
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // 4. CORS: deja que los frontends (web/app) llamen a la API desde el navegador.
                //    Los orígenes permitidos se configuran en CorsConfig (propiedad app.cors.allowed-origins).
                .cors(Customizer.withDefaults())
                // 5. Permite el acceso a las rutas de autenticación, públicas, Swagger y al manejo de errores
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/public/**", "/error").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Documentos y fotos del consultorio: solo odontólogos
                        .requestMatchers("/api/odontologos/yo/**").hasRole("ODONTOLOGO")
                        // Buscador, perfil público y catálogos: sin login (el paciente explora antes de registrarse)
                        .requestMatchers(HttpMethod.GET, "/api/odontologos", "/api/odontologos/*",
                                "/api/odontologos/*/disponibilidad", "/api/catalogos/**").permitAll()
                        // Solo un paciente reserva
                        .requestMatchers(HttpMethod.POST, "/api/citas", "/api/citas/*/pagar").hasRole("PACIENTE")
                        // Railway consulta este endpoint para saber si la app está viva
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                // 401 sin token o con token vencido; 403 si el rol no alcanza (ambos en JSON)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(respuestasSeguridad)
                        .accessDeniedHandler(respuestasSeguridad)
                )
                // 6. Inserta el filtro JWT antes del filtro estándar de autenticación por formulario
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}