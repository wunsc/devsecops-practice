using Microsoft.AspNetCore.Mvc;

namespace SampleApi.Controllers
{
    /// <summary>
    /// Provides application metadata and health information
    /// </summary>
    [ApiController]
    [Route("api/[controller]")]
    public class InfoController : ControllerBase
    {
        private readonly ILogger<InfoController> _logger;

        public InfoController(ILogger<InfoController> logger)
        {
            _logger = logger;
        }

        /// <summary>
        /// Returns application info including version and environment
        /// </summary>
        /// <returns>Application metadata object</returns>
        [HttpGet]
        [ProducesResponseType(typeof(object), 200)]
        public IActionResult GetInfo()
        {
            _logger.LogInformation("Info endpoint called at {Time}", DateTime.UtcNow);
            return Ok(new
            {
                Application = "SampleApi",
                Version = "1.1.0",
                Environment = Environment.GetEnvironmentVariable("ASPNETCORE_ENVIRONMENT") ?? "Unknown",
                Timestamp = DateTime.UtcNow,
                Runtime = System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription
            });
        }
    }
}
