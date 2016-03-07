package org.sbcoba.cloud.sample;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.http.HttpRequest;
import com.netflix.niws.client.http.RestClient;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.RestTemplateCustomizer;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.cloud.netflix.ribbon.RibbonClientHttpRequestFactory;
import org.springframework.cloud.netflix.ribbon.RibbonHttpRequest;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.rest.core.annotation.Description;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedResources;
import org.springframework.hateoas.client.Traverson;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@EnableCircuitBreaker
@EnableZuulProxy
@EnableFeignClients
@EnableDiscoveryClient
@EnableEurekaClient
@SpringBootApplication
public class SpringCloudApiSampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringCloudApiSampleApplication.class, args);
	}

	@Value("${temp.remark}")
	String tempMessage;

	@Bean
	CommandLineRunner init(MemberRepository memberRepository) {
		return a -> {
			memberRepository.save(new Member("이영형","soi", tempMessage));
			memberRepository.save(new Member("민철수","mch1", tempMessage));
			memberRepository.save(new Member("류민","ryu", tempMessage));
        };
	}
}
@Configuration
@AutoConfigureAfter(name = "org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration")
@AutoConfigureBefore(LoadBalancerAutoConfiguration.class)
class RibbonClientConfig {

	@Bean
	public RestTemplateCustomizer restTemplateCustomizer(
			RibbonClientHttpRequestFactory ribbonClientHttpRequestFactory) {
		return restTemplate -> restTemplate.setRequestFactory(ribbonClientHttpRequestFactory);
	}

	@Bean
	public RibbonClientHttpRequestFactory ribbonClientHttpRequestFactory(
			SpringClientFactory clientFactory,
			LoadBalancerClient loadBalancer,
			HttpComponentsClientHttpRequestFactory httpComponentsClientHttpRequestFactory) {
		return new RibbonClientHttpRequestFactory(clientFactory,
				loadBalancer) {
			@Override
			public ClientHttpRequest createRequest(URI originalUri, HttpMethod httpMethod)
					throws IOException {
				String serviceId = originalUri.getHost();
				if (serviceId == null) {
					throw new IOException("Invalid hostname in the URI [" + originalUri.toASCIIString() + "]");
				}
				ServiceInstance instance = loadBalancer.choose(serviceId);
				if (instance == null) {
					//log.debug("No instances available for "+serviceId);
					return httpComponentsClientHttpRequestFactory.createRequest(originalUri, httpMethod);
				}
				URI uri = loadBalancer.reconstructURI(instance, originalUri);
				IClientConfig clientConfig = clientFactory.getClientConfig(instance.getServiceId());
				RestClient client = clientFactory.getClient(instance.getServiceId(), RestClient.class);
				HttpRequest.Verb verb = HttpRequest.Verb.valueOf(httpMethod.name());
				return new RibbonHttpRequest(uri, verb, client, clientConfig);
			}
		};
	}

	@Bean
	public HttpComponentsClientHttpRequestFactory httpComponentsClientHttpRequestFactory() {
		return new HttpComponentsClientHttpRequestFactory();
	}
}
@XmlRootElement
@Data
@Entity
class Member implements Serializable {
	@Id
	@GeneratedValue
	Long id;
	String name;
	String username;
	String remark;
	public Member() {}
	public Member(String name, String username, String remark) {
		this.name = name;
		this.username = username;
		this.remark = remark;
	}
}

@RepositoryRestResource(collectionResourceDescription = @Description("테스트"))
interface MemberRepository extends PagingAndSortingRepository<Member, Long> {}

@Controller
@RequestMapping("test")
class TestController {

	@Autowired
	private DiscoveryClient discoveryClient;

	@RequestMapping("services/{serviceId}")
	@ResponseBody
	public List<ServiceInstance> serviceInstances(@PathVariable("serviceId") String serviceId) {
		return discoveryClient.getInstances(serviceId);
	}

	@RequestMapping("service")
	@ResponseBody
	public ServiceInstance serviceInstance() {
		return discoveryClient.getLocalServiceInstance();
	}

	@RequestMapping("service-names")
	@ResponseBody
	public List<String> serviceNames() {
		return discoveryClient.getServices();
	}

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	MemberIntegration memberIntegration;

	Traverson traverson;

	@PostConstruct
	public void init() throws URISyntaxException{
		this.traverson = new Traverson(new URI("http://rest-api/"), MediaTypes.HAL_JSON);
		this.traverson.setRestOperations(restTemplate);
	}

	@RequestMapping("ribbon")
	@ResponseBody
	public Collection<Member> ribbon()  {
		ParameterizedTypeReference<PagedResources<Member>> pagedResourcesType
				= new ParameterizedTypeReference<PagedResources<Member>>() {};

		Traverson.TraversalBuilder members = traverson.follow("members");
		PagedResources<Member> memberPagedResources = members.toObject(pagedResourcesType);
		if (memberPagedResources != null) {
			return memberPagedResources.getContent();
		}
//		ResponseEntity<PagedResources<Member>> responseEntityresources =
//				restTemplate.exchange("http://rest-api/members", HttpMethod.GET, null, pagedResourcesType);
//		if (responseEntityresources != null) {
//			return responseEntityresources.getBody().getContent();
//		}
		return Collections.emptyList();
	}

	@RequestMapping("feign")
	@ResponseBody
	public Collection<Member> feign() {
		PagedResources<Member> resources = memberIntegration.memberResources();
		System.out.println(resources);
		return resources.getContent();
	}
}

@FeignClient("rest-api")
interface MembersRestClient {

	@RequestMapping(value = "/members", method = RequestMethod.GET)
	PagedResources<Member> memberResources();
}

@Component
class MemberIntegration {

	@Autowired
	MembersRestClient membersRestClient;

	@Autowired
	RestTemplate restTemplate;


//	public Member memberFallback() {
//		return new Member("실패사용자","fail","실패한 요청입니다 ㅠㅠ");
//	}
//
//	@HystrixCommand(fallbackMethod = "memberFallback")
////	public Member member() {
////		return restTemplate.getForObject("http://rest-api/members/1", Member.class);
////	}
	public PagedResources<Member> memberResources() {
		return membersRestClient.memberResources();
	}

}