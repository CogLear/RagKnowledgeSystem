import * as React from "react";
import { useChatThemeStore } from "@/stores/chatThemeStore";

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  radius: number;
  alpha: number;
  color: string;
  life: number;
  maxLife: number;
  type: "float" | "sparkle";
}

interface FloatingOrb {
  x: number;
  y: number;
  baseY: number;
  vx: number;
  radius: number;
  alpha: number;
  hue: number;
  phase: number;
  speed: number;
  amplitude: number;
}

export function ParticleBackground({ className = "" }: ParticleBackgroundProps) {
  const { theme } = useChatThemeStore();
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  const animationRef = React.useRef<number>(0);
  const particlesRef = React.useRef<Particle[]>([]);
  const orbsRef = React.useRef<FloatingOrb[]>([]);
  const auroraLinePhaseRef = React.useRef(0);

  const isDark = theme === "aurora";

  React.useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d")!;
    // ctx is guaranteed non-null for the lifetime of this effect

    let width = window.innerWidth;
    let height = window.innerHeight;
    canvas.width = width;
    canvas.height = height;

    // Aurora colors (dark theme)
    const auroraColors = [
      "rgba(139, 92, 246,", // purple
      "rgba(59, 130, 246,", // blue
      "rgba(168, 85, 247,", // violet
      "rgba(236, 72, 153,", // pink
      "rgba(255, 255, 255," // white
    ];

    // Crystal colors (light theme) - vibrant floating orbs
    const crystalHues = [220, 200, 180, 260, 45, 320]; // blue, cyan, pink, purple, yellow, magenta

    function createParticle(): Particle {
      const color = auroraColors[Math.floor(Math.random() * auroraColors.length)];
      return {
        x: Math.random() * width,
        y: Math.random() * height,
        vx: (Math.random() - 0.5) * 0.8,
        vy: (Math.random() - 0.5) * 0.8 - 0.2,
        radius: Math.random() * 3 + 1,
        alpha: Math.random() * 0.5 + 0.3,
        color,
        life: 0,
        maxLife: Math.random() * 200 + 100,
        type: Math.random() > 0.7 ? "sparkle" : "float"
      };
    }

    function createOrb(): FloatingOrb {
      return {
        x: Math.random() * width,
        y: Math.random() * height,
        baseY: Math.random() * height,
        vx: (Math.random() - 0.5) * 0.8,
        radius: Math.random() * 100 + 60,
        alpha: Math.random() * 0.4 + 0.25,
        hue: crystalHues[Math.floor(Math.random() * crystalHues.length)],
        phase: Math.random() * Math.PI * 2,
        speed: Math.random() * 1.2 + 0.5,
        amplitude: Math.random() * 60 + 30
      };
    }

    function initParticles() {
      const count = isDark ? 80 : 0;
      particlesRef.current = [];
      for (let i = 0; i < count; i++) {
        const p = createParticle();
        p.life = Math.random() * p.maxLife;
        particlesRef.current.push(p);
      }
    }

    function initOrbs() {
      const count = isDark ? 0 : 25;
      orbsRef.current = [];
      for (let i = 0; i < count; i++) {
        orbsRef.current.push(createOrb());
      }
    }

    initParticles();
    initOrbs();

    function drawAuroraBackground() {
      // Deep space gradient
      const bgGradient = ctx.createLinearGradient(0, 0, width, height);
      bgGradient.addColorStop(0, "#0f0c29");
      bgGradient.addColorStop(0.5, "#302b63");
      bgGradient.addColorStop(1, "#24243e");
      ctx.fillStyle = bgGradient;
      ctx.fillRect(0, 0, width, height);

      // Aurora waves
      auroraLinePhaseRef.current += 0.003;
      const phase = auroraLinePhaseRef.current;

      for (let a = 0; a < 3; a++) {
        ctx.save();
        ctx.globalAlpha = 0.1 + a * 0.05;
        const gradient = ctx.createLinearGradient(0, 0, width, 0);
        gradient.addColorStop(0, "rgba(139, 92, 246, 0.3)");
        gradient.addColorStop(0.5, "rgba(59, 130, 246, 0.3)");
        gradient.addColorStop(1, "rgba(168, 85, 247, 0.3)");
        ctx.strokeStyle = gradient;
        ctx.lineWidth = 60 + a * 20;

        ctx.beginPath();
        for (let x = 0; x <= width; x += 5) {
          const y =
            height * 0.3 +
            a * height * 0.15 +
            Math.sin(x * 0.003 + phase + a) * 50 +
            Math.sin(x * 0.007 + phase * 1.5) * 30;
          if (x === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.stroke();
        ctx.restore();
      }
    }

    function drawCrystalBackground() {
      // Light gradient base
      const bgGradient = ctx.createLinearGradient(0, 0, width, height);
      bgGradient.addColorStop(0, "#e8f4fd");
      bgGradient.addColorStop(0.5, "#f0f7ff");
      bgGradient.addColorStop(1, "#e8f0fe");
      ctx.fillStyle = bgGradient;
      ctx.fillRect(0, 0, width, height);

      // Floating gradient orbs - larger and more vibrant
      orbsRef.current.forEach((orb) => {
        orb.phase += orb.speed * 0.02;
        orb.x += orb.vx;
        orb.y = orb.baseY + Math.sin(orb.phase) * orb.amplitude;

        if (orb.x < -orb.radius) orb.x = width + orb.radius;
        if (orb.x > width + orb.radius) orb.x = -orb.radius;

        ctx.save();
        ctx.globalAlpha = orb.alpha;

        const gradient = ctx.createRadialGradient(orb.x, orb.y, 0, orb.x, orb.y, orb.radius);
        gradient.addColorStop(0, `hsla(${orb.hue}, 90%, 65%, 1)`);
        gradient.addColorStop(0.3, `hsla(${orb.hue}, 85%, 70%, 0.8)`);
        gradient.addColorStop(0.6, `hsla(${orb.hue}, 80%, 75%, 0.4)`);
        gradient.addColorStop(1, `hsla(${orb.hue}, 70%, 85%, 0)`);

        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.arc(orb.x, orb.y, orb.radius, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
      });

      // Dynamic wave lines - more visible
      ctx.save();
      ctx.globalAlpha = 0.12;
      for (let i = 0; i < 15; i++) {
        ctx.beginPath();
        ctx.strokeStyle = "#818cf8";
        ctx.lineWidth = 2;
        const offset = ((Date.now() * 0.02 + i * 120) % (height + 200)) - 100;
        ctx.moveTo(0, offset);
        for (let x = 0; x <= width; x += 10) {
          const y = offset + Math.sin(x * 0.01 + i * 0.6) * 50 + Math.sin(x * 0.02 + i * 0.3) * 25;
          ctx.lineTo(x, y);
        }
        ctx.stroke();
      }
      ctx.restore();
    }

    function drawParticles() {
      particlesRef.current.forEach((p, i) => {
        p.x += p.vx;
        p.y += p.vy;
        p.life++;

        const lifeRatio = p.life / p.maxLife;
        if (lifeRatio < 0.2) {
          p.alpha = (lifeRatio / 0.2) * 0.6;
        } else if (lifeRatio > 0.8) {
          p.alpha = ((1 - lifeRatio) / 0.2) * 0.6;
        } else {
          p.alpha = 0.6;
        }

        if (p.x < 0) p.x = width;
        if (p.x > width) p.x = 0;
        if (p.y < 0) p.y = height;
        if (p.y > height) p.y = 0;

        if (p.life >= p.maxLife) {
          particlesRef.current[i] = createParticle();
          return;
        }

        ctx.save();
        ctx.globalAlpha = p.alpha;

        // Glow
        const glowGradient = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.radius * 3);
        glowGradient.addColorStop(0, `${p.color} 1)`);
        glowGradient.addColorStop(1, `${p.color} 0)`);
        ctx.fillStyle = glowGradient;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius * 3, 0, Math.PI * 2);
        ctx.fill();

        // Core
        ctx.fillStyle = `${p.color} 1)`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fill();

        // Sparkle effect
        if (p.type === "sparkle") {
          ctx.globalAlpha = p.alpha * 0.8;
          ctx.strokeStyle = `${p.color} 1)`;
          ctx.lineWidth = 0.5;
          for (let angle = 0; angle < Math.PI * 2; angle += Math.PI / 4) {
            ctx.beginPath();
            ctx.moveTo(p.x + Math.cos(angle) * p.radius, p.y + Math.sin(angle) * p.radius);
            ctx.lineTo(
              p.x + Math.cos(angle) * p.radius * 2.5,
              p.y + Math.sin(angle) * p.radius * 2.5
            );
            ctx.stroke();
          }
        }

        ctx.restore();
      });

      // Aurora connections
      const connDist = 120;
      ctx.save();
      particlesRef.current.forEach((p1, i) => {
        particlesRef.current.slice(i + 1).forEach((p2) => {
          const dx = p1.x - p2.x;
          const dy = p1.y - p2.y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < connDist) {
            ctx.globalAlpha = (1 - dist / connDist) * 0.15;
            ctx.strokeStyle = "rgba(139, 92, 246, 0.5)";
            ctx.lineWidth = 0.5;
            ctx.beginPath();
            ctx.moveTo(p1.x, p1.y);
            ctx.lineTo(p2.x, p2.y);
            ctx.stroke();
          }
        });
      });
      ctx.restore();
    }

    function draw() {
      ctx.clearRect(0, 0, width, height);

      if (isDark) {
        drawAuroraBackground();
        drawParticles();
      } else {
        drawCrystalBackground();
      }

      animationRef.current = requestAnimationFrame(draw);
    }

    draw();

    function handleResize() {
      if (!canvas) return;
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = width;
      canvas.height = height;
      initOrbs();
    }

    window.addEventListener("resize", handleResize);

    return () => {
      cancelAnimationFrame(animationRef.current);
      window.removeEventListener("resize", handleResize);
    };
  }, [isDark]);

  return <canvas ref={canvasRef} className={`fixed inset-0 ${className}`} style={{ zIndex: 0 }} />;
}

interface ParticleBackgroundProps {
  className?: string;
}
