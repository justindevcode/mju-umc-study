package classproject.dearme.service.user;

import classproject.dearme.domain.user.Authority;
import classproject.dearme.domain.user.Users;
import classproject.dearme.dto.Response;
import classproject.dearme.dto.userjwt.UserRequestDto;
import classproject.dearme.dto.userjwt.UserResponseDto;
import classproject.dearme.jwt.JwtTokenProvider;
import classproject.dearme.repository.user.UsersRepository;
import classproject.dearme.security.SecurityUtil;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserJwtService {

	private final UsersRepository usersRepository;
	private final Response response;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final AuthenticationManagerBuilder authenticationManagerBuilder;
	private final RedisTemplate redisTemplate;

	public ResponseEntity<?> signUp(UserRequestDto.SignUp signUp) {
		if (usersRepository.existsByUsername(signUp.getUsername())) {
			return response.fail("이미 회원가입된 이메일입니다.", HttpStatus.BAD_REQUEST);
		}

		Users user = Users.builder()
			.username(signUp.getUsername())
			.password(passwordEncoder.encode(signUp.getPassword()))
			.roles(Collections.singletonList(Authority.ROLE_USER.name()))
			.phone(signUp.getPhone())
			.email(signUp.getEmail())
			.build();
		usersRepository.save(user);

		return response.success("회원가입에 성공했습니다.");
	}

	public ResponseEntity<?> login(UserRequestDto.Login login) {

		if (usersRepository.findByUsername(login.getUsername()).orElse(null) == null) {
			return response.fail("해당하는 유저가 존재하지 않습니다.", HttpStatus.BAD_REQUEST);
		}

		// 1. Login ID/PW 를 기반으로 Authentication 객체 생성
		// 이때 authentication 는 인증 여부를 확인하는 authenticated 값이 false
		UsernamePasswordAuthenticationToken authenticationToken = login.toAuthentication();

		// 2. 실제 검증 (사용자 비밀번호 체크)이 이루어지는 부분
		// authenticate 매서드가 실행될 때 CustomUserDetailsService 에서 만든 loadUserByUsername 메서드가 실행
		Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);

		// 3. 인증 정보를 기반으로 JWT 토큰 생성
		UserResponseDto.TokenInfo tokenInfo = jwtTokenProvider.generateToken(authentication);

		// 4. RefreshToken Redis 저장 (expirationTime 설정을 통해 자동 삭제 처리)
		log.info("레디스 저장전");
		redisTemplate.opsForValue()
			.set("RT:" + authentication.getName(), tokenInfo.getRefreshToken(), tokenInfo.getRefreshTokenExpirationTime(), TimeUnit.MILLISECONDS);

		log.info("리턴전");
		return response.success(tokenInfo, "로그인에 성공했습니다.", HttpStatus.OK);
	}

	public ResponseEntity<?> reissue(UserRequestDto.Reissue reissue) {
		// 1. Refresh Token 검증
		if (!jwtTokenProvider.validateToken(reissue.getRefreshToken())) {
			return response.fail("Refresh Token 정보가 유효하지 않습니다.", HttpStatus.BAD_REQUEST);
		}

		// 2. Access Token 에서 User email 을 가져옵니다.
		Authentication authentication = jwtTokenProvider.getAuthentication(reissue.getAccessToken());

		// 3. Redis 에서 User email 을 기반으로 저장된 Refresh Token 값을 가져옵니다.
		String refreshToken = (String)redisTemplate.opsForValue().get("RT:" + authentication.getName());
		// (추가) 로그아웃되어 Redis 에 RefreshToken 이 존재하지 않는 경우 처리
		if(ObjectUtils.isEmpty(refreshToken)) {
			return response.fail("잘못된 요청입니다.", HttpStatus.BAD_REQUEST);
		}
		if(!refreshToken.equals(reissue.getRefreshToken())) {
			return response.fail("Refresh Token 정보가 일치하지 않습니다.", HttpStatus.BAD_REQUEST);
		}

		// 4. 새로운 토큰 생성
		UserResponseDto.TokenInfo tokenInfo = jwtTokenProvider.generateToken(authentication);

		// 5. RefreshToken Redis 업데이트
		redisTemplate.opsForValue()
			.set("RT:" + authentication.getName(), tokenInfo.getRefreshToken(), tokenInfo.getRefreshTokenExpirationTime(), TimeUnit.MILLISECONDS);

		return response.success(tokenInfo, "Token 정보가 갱신되었습니다.", HttpStatus.OK);
	}

	public ResponseEntity<?> logout(UserRequestDto.Logout logout) {
		// 1. Access Token 검증
		if (!jwtTokenProvider.validateToken(logout.getAccessToken())) {
			return response.fail("잘못된 요청입니다.", HttpStatus.BAD_REQUEST);
		}

		// 2. Access Token 에서 User email 을 가져옵니다.
		Authentication authentication = jwtTokenProvider.getAuthentication(logout.getAccessToken());

		// 3. Redis 에서 해당 User email 로 저장된 Refresh Token 이 있는지 여부를 확인 후 있을 경우 삭제합니다.
		if (redisTemplate.opsForValue().get("RT:" + authentication.getName()) != null) {
			// Refresh Token 삭제
			redisTemplate.delete("RT:" + authentication.getName());
		}

		// 4. 해당 Access Token 유효시간 가지고 와서 BlackList 로 저장하기
		Long expiration = jwtTokenProvider.getExpiration(logout.getAccessToken());
		redisTemplate.opsForValue()
			.set(logout.getAccessToken(), "logout", expiration, TimeUnit.MILLISECONDS);

		return response.success("로그아웃 되었습니다.");
	}

	public ResponseEntity<?> authority() {
		// SecurityContext에 담겨 있는 authentication userEamil 정보
		String userEmail = SecurityUtil.getCurrentUserName();

		Users user = usersRepository.findByUsername(userEmail)
			.orElseThrow(() -> new UsernameNotFoundException("No authentication information."));

		// add ROLE_ADMIN
		user.getRoles().add(Authority.ROLE_ADMIN.name());
		usersRepository.save(user);

		return response.success();
	}
}